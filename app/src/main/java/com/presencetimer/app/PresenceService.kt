package com.presencetimer.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.Observer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (type "camera"): keeps face/body tracking and the timer running while the
 * app is minimized, shows the live timer in the notification panel, draws the tiny dim overlay
 * timer over other apps, and plays the alarm.
 */
class PresenceService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.presencetimer.app.START"
        const val ACTION_STOP = "com.presencetimer.app.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.presencetimer.app.TOGGLE_PAUSE"
        private const val CHANNEL_ID = "presence_timer"
        private const val NOTIF_ID = 1001

        /** Periodic mode: how long the camera looks for a human during one check. */
        private const val CHECK_WINDOW_MS = 6_000L
        /** Periodic mode: give up on a check if the camera delivers no picture at all. */
        private const val NO_FRAME_TIMEOUT_MS = 15_000L
        /** Periodic mode: after a check that could not see anything, retry sooner than the interval. */
        private const val UNSEEN_RETRY_MS = 30_000L
        /** PC link-lost alarm: if the PC never connects after Start, arm the alarm after this long. */
        private const val LINK_FIRST_CONNECT_GRACE_MS = 20_000L

        @Volatile private var instance: PresenceService? = null
        @Volatile private var pendingProvider: Preview.SurfaceProvider? = null

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, PresenceService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            if (instance != null) {
                ctx.startService(Intent(ctx, PresenceService::class.java).setAction(ACTION_STOP))
            } else {
                Engine.stopDetection()
            }
        }

        /** Called from the UI (main thread) to show / hide the camera preview. */
        fun setPreviewProvider(provider: Preview.SurfaceProvider?) {
            pendingProvider = provider
            instance?.applyPreview()
        }
    }

    private enum class AlarmReason { DETECTED, MISSING, LINK_LOST }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var analyzer: PresenceAnalyzer
    private lateinit var overlay: OverlayController
    private lateinit var alarm: AlarmPlayer
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tickJob: Job? = null
    private var alarmReason: AlarmReason? = null
    private var appVisible = true
    private var running = false
    private var pcMode = false
    private val frameBusy = AtomicBoolean(false)

    // PC link-lost alarm: only armed once the PC has connected (or after a start-up grace period),
    // so pressing Start does not buzz while the phone is still searching for the PC for the first time.
    private var linkArmed = false
    private var startedAt = 0L

    // ---- periodic ("check every N") mode state, all touched on the main thread except the two flags ----
    @Volatile private var periodic = false
    @Volatile private var sleeping = false      // camera/stream off until the next check
    private var checking = false                // a check window is open
    private var holding = false                 // nobody found but person-missing alarm is on: stay awake
    private var checkStart = 0L
    private var checkFirstFrame = 0L
    private var sleepFrom = 0L
    private var sleepLenOverride = 0L           // >0: wake after this instead of the normal interval

    private val camStateObserver = Observer<CameraState> { state ->
        val err = state.error
        when {
            err == null && state.type == CameraState.Type.OPEN -> Engine.reportError(null)
            err?.code == CameraState.ERROR_CAMERA_IN_USE ->
                Engine.reportError("Camera is in use by another app.")
            err?.code == CameraState.ERROR_CAMERA_DISABLED ->
                Engine.reportError("Camera is disabled (check Android privacy settings).")
            err != null -> Engine.reportError("Camera error (code ${err.code}).")
        }
    }

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { appVisible = true; refreshOutputs() }
        override fun onStop(owner: LifecycleOwner) { appVisible = false; refreshOutputs() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        analysisExecutor = Executors.newSingleThreadExecutor()
        analyzer = PresenceAnalyzer(
            onResult = { r ->
                Engine.onFrame(r, System.currentTimeMillis())
                mainHandler.post { onFrameAnalyzed(); evaluateAlarm() }
            },
            onError = { msg -> Engine.reportError(msg) }
        )
        overlay = OverlayController(
            this,
            // Double tap toggles: running -> pause, paused -> start immediately
            onDoubleTap = {
                if (Engine.isPaused()) startNow()
                else { Engine.setPaused(true); refreshOutputs(); evaluateAlarm() }
            }
        )
        alarm = AlarmPlayer(this)
        createChannel()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)

        // React to settings changes (overlay on/off, alarm toggles) while running.
        lifecycleScope.launch {
            Store.settingsFlow.collect { syncPeriodicMode(); refreshOutputs(); evaluateAlarm() }
        }
        // React to the PC connection dropping / returning immediately (not only on the 1 s tick).
        lifecycleScope.launch {
            PcLink.status.collect { refreshOutputs(); evaluateAlarm() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_TOGGLE_PAUSE -> {
                if (running) {
                    if (Engine.isPaused()) startNow() else { Engine.setPaused(true); refreshOutputs(); evaluateAlarm() }
                }
            }
            else -> startDetection()
        }
        // STICKY: if the system kills the process, it recreates the service (null intent -> startDetection()).
        // A deliberate Stop calls stopSelf(), which is never restarted.
        return START_STICKY
    }

    /**
     * Start / resume the countdown right now. In periodic mode the camera may be asleep until the next
     * check (e.g. 15 minutes away), so resuming forces an immediate check instead of waiting for it.
     */
    private fun startNow() {
        if (!running) return
        if (Engine.isPaused()) Engine.setPaused(false)
        if (periodic && !Engine.isPresent()) {
            val now = System.currentTimeMillis()
            if (sleeping) beginCheck(now)
            else {
                holding = false; checking = true
                checkStart = now; checkFirstFrame = 0L
                Engine.setCheckState(true, false, 0L)
            }
        }
        refreshOutputs(); evaluateAlarm()
    }

    /** User swiped the app out of Recents: keep tracking, timer, notification and overlay running. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (running) {
            acquireWakeLock()
            refreshOutputs()
        }
    }

    // ---------- start / stop ----------

    private fun startDetection() {
        if (running) return

        pcMode = Store.settingsFlow.value.cameraSource == AppSettings.SOURCE_PC
        if (pcMode && Store.pairingFlow.value == null) {
            try { startForegroundCompat() } catch (_: Exception) { }
            Engine.reportError("Pair with your PC first (Camera source card).")
            shutdown()
            return
        }

        if (!pcMode && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Must still satisfy the startForegroundService() contract before stopping.
            try { startForegroundCompat() } catch (_: Exception) { }
            Engine.reportError("Camera permission denied. Allow it in Settings → Apps → Presence Timer → Permissions.")
            shutdown()
            return
        }

        try {
            startForegroundCompat()
        } catch (e: Exception) {
            Engine.reportError("Could not start background service: ${e.message}")
            shutdown()
            return
        }

        running = true
        linkArmed = false
        startedAt = System.currentTimeMillis()
        periodic = Store.settingsFlow.value.periodicEnabled
        sleeping = false; holding = false
        checking = periodic
        checkStart = System.currentTimeMillis(); checkFirstFrame = 0L
        Engine.startDetection(periodic)
        acquireWakeLock()
        if (pcMode) startPcSource() else bindCamera()

        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                Engine.tick(now)
                periodicTick(now)
                refreshOutputs()
                evaluateAlarm()
                delay(1000 - (now % 1000))   // align updates to the second
            }
        }
    }

    private fun startForegroundCompat() {
        val n = buildNotification(System.currentTimeMillis(), Engine.live.value)
        if (Build.VERSION.SDK_INT >= 34) {
            val type = if (pcMode) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                       else ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } else if (Build.VERSION.SDK_INT >= 30) {
            val type = if (pcMode) ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                       else ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- PC camera (frames streamed from PresenceCam on the laptop) ----------

    private fun startPcSource() {
        analyzer.mirror = false
        frameBusy.set(false)
        PcLink.streamEnabled = true
        PcLink.frameListener = { bytes -> onPcFrame(bytes) }
        PcLink.acquire(this, "service")
    }

    private fun stopPcSource() {
        PcLink.frameListener = null
        PcLink.release("service")
    }

    /** Runs on the link's reader thread: decode + detect the newest frame, drop frames while busy. */
    private fun onPcFrame(bytes: ByteArray) {
        if (!running || (periodic && sleeping) || !frameBusy.compareAndSet(false, true)) return
        try {
            analysisExecutor.execute {
                try {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        if (appVisible && !Store.settingsFlow.value.hideCamera) PcLink.publishPreview(bmp)
                        analyzer.analyzeBitmap(bmp)
                    }
                } catch (e: Exception) {
                    Engine.reportError("PC frame error: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    frameBusy.set(false)
                }
            }
        } catch (_: Exception) {
            frameBusy.set(false)
        }
    }

    private fun shutdown() {
        running = false
        periodic = false; sleeping = false; checking = false; holding = false
        tickJob?.cancel()
        Engine.stopDetection()
        setAlarm(null)
        overlay.hide()
        stopPcSource()
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) {
            running = false
            Engine.stopDetection()
        }
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        tickJob?.cancel()
        alarm.stop()
        overlay.hide()
        stopPcSource()
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        analyzer.close()
        analysisExecutor.shutdown()
        releaseWakeLock()
        instance = null
        super.onDestroy()
    }

    // ---------- camera ----------

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val front = CameraSelector.DEFAULT_FRONT_CAMERA
                val back = CameraSelector.DEFAULT_BACK_CAMERA
                val selector = when {
                    provider.hasCamera(front) -> front
                    provider.hasCamera(back) -> back
                    else -> throw IllegalStateException("No camera was found.")
                }
                analyzer.mirror = selector == front

                val pv = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                val analysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, analyzer)

                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, selector, pv, analysis)
                preview = pv
                applyPreview()

                // Same observer instance every time, so re-binding after a sleep never stacks observers.
                camera.cameraInfo.cameraState.observe(this, camStateObserver)
            } catch (e: Exception) {
                val msg = "Camera error: ${e.message ?: e.javaClass.simpleName}"
                Engine.reportError(msg)
                if (periodic && e !is IllegalStateException) {
                    // A temporary problem (e.g. camera busy): count this check as "could not see", try again later.
                    finishCheck(false, System.currentTimeMillis(), "⚠️ Camera unavailable — timer paused, will retry")
                } else {
                    shutdown()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyPreview() {
        try { preview?.setSurfaceProvider(pendingProvider) } catch (_: Exception) { }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PresenceTimer::Detection").apply {
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
    }

    // ---------- periodic ("check every N seconds/minutes") mode ----------
    //
    // Cycle: CHECK (camera/stream on, ML runs, up to CHECK_WINDOW_MS) -> SLEEP (camera/stream off,
    // no ML, almost no CPU) -> next CHECK after the interval. Human found at a check: the timer keeps
    // running through the sleep. Nobody found: the timer stops there. The interval is read from the
    // settings on every tick, so changing it while running takes effect immediately.

    private fun nextCheckAt(): Long =
        sleepFrom + (if (sleepLenOverride > 0L) sleepLenOverride else Store.settingsFlow.value.periodicIntervalMs)

    /** Settings changed while running: switch periodic mode on/off without stopping detection. */
    private fun syncPeriodicMode() {
        if (!running) return
        val want = Store.settingsFlow.value.periodicEnabled
        if (want == periodic) return
        val now = System.currentTimeMillis()
        if (want) {
            periodic = true
            checking = true; sleeping = false; holding = false
            checkStart = now; checkFirstFrame = 0L
            Engine.setPeriodic(true, now)
            Engine.setCheckState(true, false, 0L)
        } else {
            val wasSleeping = sleeping
            periodic = false
            checking = false; sleeping = false; holding = false
            Engine.setPeriodic(false, now)
            Engine.setCheckState(false, false, 0L)
            if (wasSleeping) wakeSource()
        }
    }

    private fun wakeSource() {
        if (pcMode) PcLink.streamEnabled = true else bindCamera()
    }

    private fun sleepSource() {
        if (pcMode) {
            PcLink.streamEnabled = false          // PC releases its webcam; link stays connected
        } else {
            try { cameraProvider?.unbindAll() } catch (_: Exception) { }   // camera light off, no frames
            preview = null
        }
    }

    private fun beginCheck(now: Long) {
        checking = true; sleeping = false; holding = false
        checkStart = now; checkFirstFrame = 0L
        Engine.setCheckState(true, false, 0L)
        wakeSource()
    }

    private fun enterSleep(now: Long, retryAfterMs: Long) {
        checking = false; holding = false; sleeping = true
        sleepFrom = now; sleepLenOverride = retryAfterMs
        sleepSource()
        setAlarm(null)
        Engine.setCheckState(false, true, nextCheckAt())
    }

    /** found = a human was confirmed. Otherwise the window ended empty ([unseenReason] = camera gave no picture). */
    private fun finishCheck(found: Boolean, now: Long, unseenReason: String? = null) {
        if (!periodic || !checking) return
        if (found) { enterSleep(now, 0L); return }
        val s = Store.settingsFlow.value
        // Person-missing alarm ON: don't sleep, keep watching until the person is back.
        val keepAwake = unseenReason == null && s.alarmEnabled && Engine.personWasSeen()
        Engine.checkFoundNobody(now, unseenReason)
        if (keepAwake) { holding = true; return }
        val retry = if (unseenReason != null) minOf(s.periodicIntervalMs, UNSEEN_RETRY_MS) else 0L
        enterSleep(now, retry)
    }

    /** Main thread, after every analyzed frame. */
    private fun onFrameAnalyzed() {
        if (!periodic || !checking) return
        val now = System.currentTimeMillis()
        if (checkFirstFrame == 0L) checkFirstFrame = now
        if (Engine.isPresent() && Engine.humanNow(now)) {
            holding = false
            finishCheck(true, now)
        }
    }

    /** Once per second. */
    private fun periodicTick(now: Long) {
        if (!periodic) return
        if (checking) {
            if (holding) return
            val noPicture = checkFirstFrame == 0L && now - checkStart >= NO_FRAME_TIMEOUT_MS
            val windowOver = checkFirstFrame != 0L && now - checkFirstFrame >= CHECK_WINDOW_MS
            if (noPicture) finishCheck(false, now, "⚠️ No camera picture — timer paused, will retry")
            else if (windowOver) finishCheck(false, now)
        } else if (sleeping) {
            val next = nextCheckAt()
            Engine.setCheckState(false, true, next)
            if (now >= next) beginCheck(now)
        }
    }

    // ---------- alarm ----------

    private fun evaluateAlarm() {
        if (!running) { setAlarm(null); return }
        if (Engine.isPaused()) { setAlarm(null); return }      // manually paused: stay silent
        val s = Store.settingsFlow.value
        val now = System.currentTimeMillis()

        // PC camera connection lost -> buzz immediately (if the toggle is on). The link stays up while the
        // camera sleeps in periodic mode, so a dropped link is detected there too.
        if (pcMode) {
            val connected = PcLink.status.value.state == LinkState.STREAMING
            if (connected) linkArmed = true
            else if (!linkArmed && now - startedAt >= LINK_FIRST_CONNECT_GRACE_MS) linkArmed = true
            if (s.linkLostAlarmEnabled && linkArmed && !connected) { setAlarm(AlarmReason.LINK_LOST); return }
        }

        if (periodic && sleeping) { setAlarm(null); return }   // camera is off between checks
        // PC link down (network switch, PC asleep) and the link-lost alarm is off: we simply cannot see
        // anyone, so never sound a false "person missing" alarm.
        if (pcMode && !PcLink.framesFlowing(now)) { setAlarm(null); return }
        val desired: AlarmReason? = when {
            Engine.humanNow(now) -> if (s.humanAlarmEnabled) AlarmReason.DETECTED else null
            Engine.inGrace() -> alarmReason?.takeIf { it != AlarmReason.LINK_LOST }   // brief flicker: keep whatever is happening
            s.alarmEnabled && Engine.personWasSeen() -> AlarmReason.MISSING
            else -> null
        }
        setAlarm(desired)
    }

    private fun setAlarm(reason: AlarmReason?) {
        if (reason == alarmReason && (reason == null || alarm.isPlaying)) return
        alarm.stop()
        alarmReason = reason
        if (reason != null) alarm.start()
        Engine.setAlarmPlaying(
            when (reason) {
                AlarmReason.DETECTED -> "🚨 ALARM PLAYING • Human detected"
                AlarmReason.MISSING -> "🚨 ALARM PLAYING • Person missing"
                AlarmReason.LINK_LOST -> "🚨 ALARM PLAYING • PC camera disconnected"
                null -> null
            }
        )
    }

    // ---------- notification + overlay ----------

    private fun refreshOutputs() {
        if (!running) return
        val now = System.currentTimeMillis()
        val live = Engine.live.value
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(now, live))

        val s = Store.settingsFlow.value
        val show = s.overlayEnabled && live.detecting && !appVisible && Settings.canDrawOverlays(this)
        if (show) overlay.show(Fmt.time(live.displayMs(now)), live.present, s.overlayPosition, live.paused)
        else overlay.hide()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_timer), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_timer_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(now: Long, live: LiveState): Notification {
        val nextIn = if (live.nextCheckAtMs > 0) Fmt.time(live.nextCheckAtMs - now) else ""
        val text = when {
            live.detecting && live.paused -> "⏸ Paused — timer frozen • tap Resume"
            live.detecting && live.periodic && live.sleeping ->
                (if (live.present) "🟢 Timer running" else "🔴 Timer stopped") + " • camera asleep • next check in $nextIn"
            live.detecting && live.periodic && live.checking && !live.present -> "🔍 Checking camera…"
            pcMode && live.detecting && !PcLink.framesFlowing(now) -> "🟠 PC camera not connected — timer paused"
            live.detecting && live.present -> "🟢 Human detected — timer running"
            live.detecting && live.statusKind == StatusKind.PAUSED -> "🔴 No face or body — timer paused"
            else -> "🟡 Looking for face or body…"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PresenceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pause = PendingIntent.getService(
            this, 2,
            Intent(this, PresenceService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setColor(0xFFFF2D3F.toInt())
            .setContentTitle(Fmt.time(live.displayMs(now)))
            .setContentText(text)
            .setSubText("Today ${Fmt.time(Store.todayMs(now))}")
            .setContentIntent(open)
            .addAction(0, if (live.paused) "Resume" else "Pause", pause)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
