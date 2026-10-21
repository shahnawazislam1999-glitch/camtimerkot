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
        private const val CHANNEL_ID = "presence_timer"
        private const val NOTIF_ID = 1001

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

    private enum class AlarmReason { DETECTED, MISSING }

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
                mainHandler.post { evaluateAlarm() }
            },
            onError = { msg -> Engine.reportError(msg) }
        )
        overlay = OverlayController(this)
        alarm = AlarmPlayer(this)
        createChannel()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)

        // React to settings changes (overlay on/off, alarm toggles) while running.
        lifecycleScope.launch {
            Store.settingsFlow.collect { refreshOutputs(); evaluateAlarm() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            else -> startDetection()
        }
        return START_NOT_STICKY
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
        Engine.startDetection()
        acquireWakeLock()
        if (pcMode) startPcSource() else bindCamera()

        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                Engine.tick(now)
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
        PcLink.frameListener = { bytes -> onPcFrame(bytes) }
        PcLink.acquire(this, "service")
    }

    private fun stopPcSource() {
        PcLink.frameListener = null
        PcLink.release("service")
    }

    /** Runs on the link's reader thread: decode + detect the newest frame, drop frames while busy. */
    private fun onPcFrame(bytes: ByteArray) {
        if (!running || !frameBusy.compareAndSet(false, true)) return
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

                camera.cameraInfo.cameraState.observe(this) { state ->
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
            } catch (e: Exception) {
                Engine.reportError("Camera error: ${e.message ?: e.javaClass.simpleName}")
                shutdown()
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

    // ---------- alarm ----------

    private fun evaluateAlarm() {
        if (!running) { setAlarm(null); return }
        val s = Store.settingsFlow.value
        val now = System.currentTimeMillis()
        // PC link down (network switch, PC asleep): we simply cannot see anyone, so never sound a false alarm.
        if (pcMode && !PcLink.framesFlowing(now)) { setAlarm(null); return }
        val desired: AlarmReason? = when {
            Engine.humanNow(now) -> if (s.humanAlarmEnabled) AlarmReason.DETECTED else null
            Engine.inGrace() -> alarmReason   // brief flicker: keep whatever is happening
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
        if (show) overlay.show(Fmt.time(live.displayMs(now)), live.present, s.overlayPosition)
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
        val text = when {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setColor(0xFFFF2D3F.toInt())
            .setContentTitle(Fmt.time(live.displayMs(now)))
            .setContentText(text)
            .setSubText("Today ${Fmt.time(Store.todayMs(now))}")
            .setContentIntent(open)
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
