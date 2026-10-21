package com.presencetimer.app

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

enum class LinkState { OFF, SEARCHING, CONNECTING, STREAMING }

data class LinkStatus(
    val state: LinkState = LinkState.OFF,
    val text: String = "Not connected",
    val pcName: String = "",
    val ip: String = "",
    val note: String? = null
)

/**
 * Process-wide handle to the laptop-camera link (see PresenceCam on the PC).
 * Owners ("service", "pairing", "test") keep the client alive; when none is left it stops.
 */
object PcLink {
    const val TCP_PORT = 47655
    const val UDP_PORT = 47656

    private val _status = MutableStateFlow(LinkStatus())
    val status: StateFlow<LinkStatus> = _status

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview

    /** One-line result of the last pairing attempt (shown in the UI). */
    val notice = MutableStateFlow<String?>(null)

    @Volatile var lastFrameAt = 0L
    @Volatile var frameListener: ((ByteArray) -> Unit)? = null

    private val lock = Any()
    private val owners = HashSet<String>()
    private var client: PcClient? = null
    private val main = Handler(Looper.getMainLooper())

    fun acquire(ctx: Context, who: String) {
        synchronized(lock) {
            owners.add(who)
            if (client == null) {
                client = PcClient(ctx.applicationContext).also { it.start() }
            }
        }
    }

    fun release(who: String) {
        synchronized(lock) {
            owners.remove(who)
            if (owners.isEmpty()) {
                client?.stop()
                client = null
                _status.value = LinkStatus()
                _preview.value = null
                lastFrameAt = 0L
            }
        }
    }

    internal fun wantsStream(): Boolean = synchronized(lock) { owners.contains("service") }

    /** Starts looking for a PC that accepts [code] (the 6 digits shown in the PC window). */
    fun beginPairing(ctx: Context, code: String) {
        notice.value = null
        acquire(ctx, "pairing")
        client?.setPairCode(code)
    }

    fun cancelPairing() {
        client?.setPairCode(null)
        release("pairing")
    }

    /** Opens the link for ~15 s so the user can see "Connected" before starting detection. */
    fun testConnection(ctx: Context) {
        acquire(ctx, "test")
        main.postDelayed({ release("test") }, 15000)
    }

    fun forgetPc() {
        Store.clearPairing()
        notice.value = "PC forgotten on this phone."
        client?.kick()
    }

    fun framesFlowing(now: Long = System.currentTimeMillis()): Boolean =
        _status.value.state == LinkState.STREAMING && now - lastFrameAt < 3000

    internal fun setStatus(s: LinkStatus) { _status.value = s }
    internal fun publishPreview(b: Bitmap) { _preview.value = b }
    internal fun onFrame(bytes: ByteArray) {
        lastFrameAt = System.currentTimeMillis()
        frameListener?.invoke(bytes)
    }
    internal fun pairingFinished(message: String) {
        notice.value = message
        main.post { release("pairing") }
    }
}

private class Beacon(val ip: String, val port: Int, val pcId: String, val name: String, val seenAt: Long)
private class Target(val ip: String, val port: Int, val name: String)
private class Frame(val type: Int, val payload: ByteArray)

private const val T_CHALLENGE = 1
private const val T_AUTH = 2
private const val T_AUTH_OK = 3
private const val T_AUTH_FAIL = 4
private const val T_FRAME = 5
private const val T_PING = 6
private const val T_PONG = 7
private const val T_STATUS = 8
private const val T_START = 10

private const val R_FAILED = 0
private const val R_ENDED = 1
private const val R_REJECTED = 2
private const val R_BAD_CODE = 3

/**
 * Finds the PC (UDP beacon, last known address, USB loopback, subnet scan), authenticates,
 * receives JPEG frames and reconnects by itself whenever the network changes.
 */
private class PcClient(private val ctx: Context) {

    @Volatile private var stopped = false
    @Volatile private var pairCode: String? = null
    @Volatile private var pairDeadline = 0L
    @Volatile private var socket: Socket? = null
    @Volatile private var udp: DatagramSocket? = null

    private val beacons = ConcurrentHashMap<String, Beacon>()
    private val badCodeIps = ConcurrentHashMap<String, Long>()
    private val kickSem = Semaphore(0)
    private val writeLock = Any()
    private var lastNetEvent = 0L
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        stopped = false
        acquireLocks()
        registerNetworkCallback()
        thread(name = "pc-beacon", isDaemon = true) { beaconLoop() }
        thread(name = "pc-link", isDaemon = true) { mainLoop() }
    }

    fun stop() {
        stopped = true
        unregisterNetworkCallback()
        releaseLocks()
        try { udp?.close() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        kickSem.release()
    }

    fun kick() { kickSem.release() }

    fun setPairCode(code: String?) {
        pairCode = code
        badCodeIps.clear()
        pairDeadline = if (code != null) SystemClock.elapsedRealtime() + 40000 else 0L
        if (code != null) {
            // Drop an existing connection so pairing starts cleanly.
            try { socket?.close() } catch (_: Exception) { }
        }
        kickSem.release()
    }

    private fun acquireLocks() {
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("presencetimer-pc").apply {
                setReferenceCounted(false); acquire()
            }
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                       else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "presencetimer-pc").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) { }
    }

    private fun releaseLocks() {
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) { }
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) { }
        multicastLock = null; wifiLock = null
    }

    private fun registerNetworkCallback() {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { networkChanged() }
                override fun onLost(network: Network) { networkChanged() }
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { networkChanged() }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            netCallback = cb
        } catch (_: Exception) { }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            netCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) { }
        netCallback = null
    }

    /** Wi-Fi / hotspot changed: forget stale addresses, rebind discovery, retry right away. */
    private fun networkChanged() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNetEvent < 700) return
        lastNetEvent = now
        beacons.clear()
        try { udp?.close() } catch (_: Exception) { }
        kickSem.release()
    }

    // ---------------------------------------------------------------- discovery

    private fun beaconLoop() {
        while (!stopped) {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(null as SocketAddress?)
                s.reuseAddress = true
                s.bind(InetSocketAddress(PcLink.UDP_PORT))
                s.broadcast = true
                s.soTimeout = 1000
                udp = s
                val buf = ByteArray(1024)
                while (!stopped) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                    handleBeacon(p)
                }
            } catch (_: Exception) {
                if (!stopped) sleepQuiet(1200)
            } finally {
                try { s?.close() } catch (_: Exception) { }
            }
        }
    }

    private fun handleBeacon(p: DatagramPacket) {
        try {
            val j = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
            if (j.optString("app") != "presencecam") return
            val ip = p.address?.hostAddress ?: return
            beacons[ip] = Beacon(
                ip, j.optInt("port", PcLink.TCP_PORT), j.optString("pc"),
                j.optString("name", "PC"), SystemClock.elapsedRealtime()
            )
            kickSem.release()
        } catch (_: Exception) { }
    }

    /** Unicast scan of the local subnet(s) + USB loopback; used when no beacon arrives (e.g. hotspot blocks broadcast). */
    private fun scanNetwork() {
        val hosts = LinkedHashSet<String>()
        hosts.add("127.0.0.1")
        for ((addr, prefix) in localIpv4()) hosts.addAll(hostsOf(addr, prefix))
        val pool = Executors.newFixedThreadPool(48)
        for (h in hosts) pool.execute { if (!stopped) probe(h) }
        pool.shutdown()
        try { pool.awaitTermination(8, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        pool.shutdownNow()
    }

    private fun probe(host: String) {
        var s: Socket? = null
        try {
            s = newSocket(host)
            s.connect(InetSocketAddress(host, PcLink.TCP_PORT), 350)
            s.soTimeout = 1200
            val f = readFrame(DataInputStream(s.getInputStream()), 4096) ?: return
            if (f.type != T_CHALLENGE) return
            val j = JSONObject(String(f.payload, Charsets.UTF_8))
            beacons[host] = Beacon(host, PcLink.TCP_PORT, j.optString("pc"), j.optString("name", "PC"),
                SystemClock.elapsedRealtime())
        } catch (_: Exception) {
        } finally {
            try { s?.close() } catch (_: Exception) { }
        }
    }

    private fun localIpv4(): List<Pair<Inet4Address, Int>> {
        val out = ArrayList<Pair<Inet4Address, Int>>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in ifs) {
                try {
                    if (!ni.isUp || ni.isLoopback) continue
                    val name = ni.name.lowercase()
                    if (DENY_IFACES.any { name.startsWith(it) }) continue
                    for (ia in ni.interfaceAddresses) {
                        val a = ia.address
                        if (a is Inet4Address && a.isSiteLocalAddress) out.add(Pair(a, ia.networkPrefixLength.toInt()))
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun hostsOf(a: Inet4Address, prefix: Int): List<String> {
        val p = maxOf(prefix, 24)
        if (p > 30) return emptyList()
        val b = a.address
        val ip = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
            ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
        val mask = -1 shl (32 - p)
        val net = ip and mask
        val count = 1 shl (32 - p)
        val res = ArrayList<String>()
        for (i in 1 until count - 1) {
            val h = net or i
            if (h == ip) continue
            res.add("${(h ushr 24) and 255}.${(h ushr 16) and 255}.${(h ushr 8) and 255}.${h and 255}")
        }
        return res
    }

    /** Socket bound to the network that owns [host]'s subnet, so a Wi-Fi without internet is still used. */
    private fun newSocket(host: String): Socket {
        val net = networkFor(host)
        return if (net != null) net.socketFactory.createSocket() else Socket()
    }

    @Suppress("DEPRECATION")
    private fun networkFor(host: String): Network? {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val target = InetAddress.getByName(host)
            if (target.isLoopbackAddress) return null
            for (n in cm.allNetworks) {
                val lp = cm.getLinkProperties(n) ?: continue
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address && sameSubnet(a, target, la.prefixLength)) return n
                }
            }
        } catch (_: Exception) { }
        return null
    }

    private fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val x = a.address; val y = b.address
        if (x.size != y.size) return false
        var bits = prefix
        for (i in x.indices) {
            val m = if (bits >= 8) 0xFF else if (bits <= 0) 0 else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and m) != (y[i].toInt() and m)) return false
            bits -= 8
        }
        return true
    }

    // ---------------------------------------------------------------- main loop

    private fun mainLoop() {
        val failedAt = ConcurrentHashMap<String, Long>()
        var searchSince = SystemClock.elapsedRealtime()
        var lastScan = 0L
        var lastIpTried = 0L
        setStatus(LinkState.SEARCHING, "Searching for your PC…")

        while (!stopped) {
            kickSem.drainPermits()
            if (checkPairDeadline()) { sleepKick(300); continue }
            if (stopped) break

            val now = SystemClock.elapsedRealtime()
            val target = pickTarget(failedAt, now, lastIpTried)
            if (target != null) {
                if (target.ip == Store.pairingFlow.value?.lastIp) lastIpTried = now
                setStatus(LinkState.CONNECTING, "Connecting to ${target.name.ifEmpty { target.ip }}…", target.name, target.ip)
                val result = runSession(target)
                if (stopped) break
                when (result) {
                    R_FAILED -> failedAt[target.ip] = SystemClock.elapsedRealtime()
                    R_BAD_CODE -> badCodeIps[target.ip] = SystemClock.elapsedRealtime()
                    R_REJECTED -> failedAt[target.ip] = SystemClock.elapsedRealtime()
                }
                searchSince = SystemClock.elapsedRealtime()
                setStatus(LinkState.SEARCHING, searchingText(result == R_ENDED))
                sleepKick(if (result == R_ENDED) 400 else 800)
                continue
            }

            // nothing to connect to yet
            if (now - searchSince < 2200) { sleepKick(300); continue }
            if (now - lastScan > 7000) {
                lastScan = now
                setStatus(LinkState.SEARCHING, if (pairCode != null) "Scanning the network for a PC to pair…"
                    else "Scanning the network for your PC…")
                scanNetwork()
                continue
            }
            sleepKick(500)
        }
    }

    private fun searchingText(wasConnected: Boolean): String = when {
        pairCode != null -> "Looking for a PC to pair with…"
        wasConnected -> "Connection lost — reconnecting…"
        else -> "Searching for your PC…"
    }

    private fun pickTarget(failedAt: Map<String, Long>, now: Long, lastIpTried: Long): Target? {
        fun ready(ip: String) = now - (failedAt[ip] ?: 0L) > 2500
        val fresh = beacons.values.filter { now - it.seenAt < 8000 }.sortedByDescending { it.seenAt }

        if (pairCode != null) {
            val b = fresh.firstOrNull { badCodeIps[it.ip] == null && ready(it.ip) } ?: return null
            return Target(b.ip, b.port, b.name)
        }
        val paired = Store.pairingFlow.value ?: return null
        val b = fresh.firstOrNull { it.pcId == paired.pcId && ready(it.ip) }
        if (b != null) return Target(b.ip, b.port, paired.pcName)
        // Beacon missing (broadcast blocked / just switched network): try the last known address and USB.
        if (now - lastIpTried > 5000) {
            if (paired.lastIp.isNotEmpty() && ready(paired.lastIp)) return Target(paired.lastIp, paired.port, paired.pcName)
        }
        if (ready("127.0.0.1") && now - lastIpTried > 5000) return Target("127.0.0.1", PcLink.TCP_PORT, paired.pcName)
        return null
    }

    /** Ends a pairing attempt that timed out or was refused by every PC found. Returns true if it ended one. */
    private fun checkPairDeadline(): Boolean {
        if (pairCode == null) return false
        val now = SystemClock.elapsedRealtime()
        val fresh = beacons.values.filter { now - it.seenAt < 8000 }
        val allBad = fresh.isNotEmpty() && fresh.all { badCodeIps[it.ip] != null }
        when {
            allBad -> finishPairing("Wrong code. Check the code shown in the PresenceCam window (press n there for a new one).")
            now > pairDeadline -> finishPairing("No PC accepted the code. Make sure PresenceCam is running on the PC, both are on the same Wi-Fi/hotspot, and the firewall allows it.")
            else -> return false
        }
        return true
    }

    private fun finishPairing(message: String) {
        pairCode = null
        PcLink.pairingFinished(message)
    }

    // ---------------------------------------------------------------- one session

    private fun runSession(t: Target): Int {
        var s: Socket? = null
        val alive = AtomicBoolean(true)
        try {
            s = newSocket(t.ip)
            socket = s
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(t.ip, t.port), 2500)
            s.soTimeout = 5000
            val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

            val ch = readFrame(inp, 4096) ?: return R_FAILED
            if (ch.type != T_CHALLENGE) return R_FAILED
            val cj = JSONObject(String(ch.payload, Charsets.UTF_8))
            val nonce = cj.getString("nonce")
            val pcId = cj.optString("pc")
            val pcName = cj.optString("name", t.name).ifEmpty { t.name }

            val devId = Store.deviceId()
            val code = pairCode
            val paired = Store.pairingFlow.value
            val mode: String
            val key: ByteArray
            if (code != null) {
                mode = "pair"; key = code.toByteArray(Charsets.UTF_8)
            } else if (paired != null && paired.pcId == pcId) {
                mode = "auth"; key = hexToBytes(paired.secretHex)
            } else {
                return R_FAILED   // some other PC
            }

            send(out, T_AUTH, JSONObject()
                .put("id", devId).put("name", Build.MODEL ?: "Android").put("mode", mode)
                .put("proof", hex(hmac(key, nonce + devId))).toString().toByteArray(Charsets.UTF_8))

            val res = readFrame(inp, 4096) ?: return R_FAILED
            if (res.type == T_AUTH_FAIL) {
                val reason = try { JSONObject(String(res.payload, Charsets.UTF_8)).optString("reason") } catch (_: Exception) { "" }
                if (mode == "pair") {
                    if (reason == "locked") PcLink.notice.value = "Too many wrong attempts. Wait a minute and try again."
                    return R_BAD_CODE
                }
                if (reason == "unknown_device") {
                    Store.clearPairing()
                    PcLink.notice.value = "This phone was removed on the PC. Pair again."
                } else {
                    PcLink.notice.value = "PC refused the connection ($reason). Pair again if this keeps happening."
                }
                return R_REJECTED
            }
            if (res.type != T_AUTH_OK) return R_FAILED

            if (mode == "pair") {
                val enc = hexToBytes(JSONObject(String(res.payload, Charsets.UTF_8)).getString("enc"))
                val mask = hmac(key, "secret$nonce")
                val secret = ByteArray(32) { i -> (enc[i].toInt() xor mask[i].toInt()).toByte() }
                Store.savePairing(PcPairing(pcId, pcName, hex(secret), t.ip, t.port))
                pairCode = null
                PcLink.pairingFinished("Paired with $pcName ✓")
            } else {
                Store.updateLastIp(t.ip, t.port)
            }

            setStatus(LinkState.STREAMING, "Connected to $pcName (${t.ip})", pcName, t.ip)

            // Heartbeat + "start video" (sent as soon as detection is running).
            val startSent = AtomicBoolean(false)
            val lastRx = AtomicLong(SystemClock.elapsedRealtime())
            val sock: Socket = s!!
            thread(name = "pc-ping", isDaemon = true) {
                var i = 0
                try {
                    while (alive.get() && !stopped) {
                        Thread.sleep(500)
                        if (!startSent.get() && PcLink.wantsStream()) {
                            send(out, T_START, "{}".toByteArray())
                            startSent.set(true)
                        }
                        if (++i % 4 == 0) send(out, T_PING, ByteArray(0))
                        if (SystemClock.elapsedRealtime() - lastRx.get() > 4500) {
                            try { sock.close() } catch (_: Exception) { }
                        }
                    }
                } catch (_: Exception) {
                    try { sock.close() } catch (_: Exception) { }
                }
            }

            while (!stopped) {
                val f = readFrame(inp, 4 * 1024 * 1024) ?: break
                lastRx.set(SystemClock.elapsedRealtime())
                when (f.type) {
                    T_FRAME -> PcLink.onFrame(f.payload)
                    T_STATUS -> handleCameraStatus(f.payload, pcName, t.ip)
                }
            }
            return R_ENDED
        } catch (_: Exception) {
            return if (PcLink.status.value.state == LinkState.STREAMING) R_ENDED else R_FAILED
        } finally {
            alive.set(false)
            try { s?.close() } catch (_: Exception) { }
            if (socket === s) socket = null
        }
    }

    private fun handleCameraStatus(payload: ByteArray, pcName: String, ip: String) {
        try {
            val j = JSONObject(String(payload, Charsets.UTF_8))
            val cam = j.optString("camera")
            val msg = j.optString("msg")
            val note = if (cam == "ok" || cam == "idle" || cam.isEmpty()) null else msg.ifEmpty { "PC camera problem" }
            PcLink.setStatus(LinkStatus(LinkState.STREAMING, "Connected to $pcName ($ip)", pcName, ip, note))
            Engine.reportError(note)
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- helpers

    private fun setStatus(state: LinkState, text: String, pcName: String = "", ip: String = "") {
        PcLink.setStatus(LinkStatus(state, text, pcName, ip))
    }

    private fun sleepKick(ms: Long) {
        try { kickSem.tryAcquire(ms, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    private fun send(out: DataOutputStream, type: Int, payload: ByteArray) {
        synchronized(writeLock) {
            out.writeByte(type)
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    private fun readFrame(inp: DataInputStream, max: Int): Frame? {
        val t = inp.read()
        if (t < 0) return null
        val len = inp.readInt()
        if (len < 0 || len > max) throw IOException("bad frame length")
        val buf = ByteArray(len)
        inp.readFully(buf)
        return Frame(t, buf)
    }

    private fun hmac(key: ByteArray, msg: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(msg.toByteArray(Charsets.UTF_8))
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x.toInt() and 0xFF))
        return sb.toString()
    }

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    companion object {
        // Mobile-data and virtual interfaces are never the PC's network.
        private val DENY_IFACES = listOf("rmnet", "ccmni", "pdp", "v4-", "dummy", "tun", "ppp", "lo", "ip6", "sit")
    }
}
