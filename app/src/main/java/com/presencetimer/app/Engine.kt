package com.presencetimer.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class StatusKind { IDLE, RUNNING, PAUSED }

/** Box in normalized (0..1) coordinates of the upright, already-mirrored preview. */
data class NBox(val l: Float, val t: Float, val r: Float, val b: Float, val label: String)

data class FaceHit(val box: NBox, val strong: Boolean)

/** What the eyes of the main face looked like in one frame (used by the eye-closed mode). */
object EyeState {
    const val NONE = 0       // no usable face
    const val OPEN = 1
    const val CLOSED = 2     // both eyes clearly closed
    const val UNKNOWN = 3    // face seen but the eyes cannot be measured (head turned far, no probabilities)
    const val MAYBE = 4      // in between (squint, half-closed, blink in progress)

    private const val CLOSED_MAX = 0.40f   // the BETTER eye is still below this -> both eyes are closed
    private const val OPEN_MIN = 0.60f     // at least one eye clearly open
    private const val MAX_YAW = 40f        // beyond this the eye probabilities are not reliable

    fun classify(left: Float?, right: Float?, yaw: Float): Int {
        if (left == null || right == null) return UNKNOWN
        if (kotlin.math.abs(yaw) > MAX_YAW) return UNKNOWN
        val best = maxOf(left, right)
        return when {
            best <= CLOSED_MAX -> CLOSED
            best >= OPEN_MIN -> OPEN
            else -> MAYBE
        }
    }
}

data class FrameResult(
    val faces: List<FaceHit>,
    val bodyDetected: Boolean,
    val bodyConfidence: Float,
    val bodyBox: NBox?,
    val verdict: VerifyVerdict? = null,  // aggressive mode: result of the multi-signal human check
    val eye: Int = EyeState.NONE         // eye-closed mode: state of the eyes of the main face
)

data class LiveState(
    val detecting: Boolean = false,
    val present: Boolean = false,
    val accumulatedMs: Long = 0,
    val sessionStartMs: Long = 0,
    val savedThroughMs: Long = 0,
    val status: String = "⚪ Ready — tap Allow Camera & Start",
    val statusKind: StatusKind = StatusKind.IDLE,
    val detectionText: String = "Camera not started",
    val alarmPlayingText: String? = null,
    val faces: List<NBox> = emptyList(),
    val body: NBox? = null,
    val error: String? = null,
    // Periodic ("check every N") mode
    val periodic: Boolean = false,
    val checking: Boolean = false,      // camera is on right now, looking for a human
    val sleeping: Boolean = false,      // camera is off until nextCheckAtMs
    val nextCheckAtMs: Long = 0,
    // Manual pause (notification button / double-tap on the floating timer)
    val paused: Boolean = false
) {
    fun displayMs(now: Long): Long =
        accumulatedMs + if (present && detecting && sessionStartMs > 0) maxOf(0L, now - sessionStartMs) else 0L
}

/**
 * Process-wide timer + presence logic, ported from index.html (detectFrame, endPresenceSession,
 * creditActiveStudy, resetTimer...). Shared by the foreground service and the UI.
 */
object Engine {
    const val START_DEBOUNCE_MS = 700L
    const val STOP_DEBOUNCE_MS = 1500L
    private const val RECENT_WINDOW = 5
    private const val RECENT_REQUIRED = 2

    private val lock = Any()
    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live

    private var inited = false
    private var accumulated = 0L
    private var savedThrough = 0L
    private var sessionStart = 0L
    private var present = false
    private var detecting = false
    private var firstSeen = 0L
    private var lastSeen = 0L
    private var dailyCursor = 0L
    private var personWasSeen = false
    private var lastFrameAt = 0L
    private var lastFrameHuman = false
    private var lastSaveAt = 0L
    private var lastNotifyAt = 0L
    private var periodic = false
    private var manualPaused = false
    private val recentWeak = ArrayDeque<Int>()

    fun init() {
        synchronized(lock) {
            if (inited) return
            inited = true
            val t = Store.loadTimer()
            accumulated = t.accumulatedMs
            savedThrough = t.savedThroughMs
            publish()
        }
        checkAutoReset(System.currentTimeMillis())
    }

    private fun publish(block: (LiveState) -> LiveState = { it }) {
        _live.update {
            block(it.copy(
                detecting = detecting, present = present,
                accumulatedMs = accumulated, sessionStartMs = sessionStart, savedThroughMs = savedThrough
            ))
        }
    }

    fun displayMs(now: Long): Long = synchronized(lock) {
        accumulated + if (present && detecting) maxOf(0L, now - sessionStart) else 0L
    }

    // ---------- lifecycle ----------

    fun startDetection(periodicMode: Boolean = false) {
        synchronized(lock) {
            periodic = periodicMode
            manualPaused = false
            detecting = true; present = false
            firstSeen = 0; lastSeen = 0; sessionStart = 0; dailyCursor = 0
            recentWeak.clear(); personWasSeen = false
            lastFrameAt = 0; lastFrameHuman = false
            publish { it.copy(status = "🟡 Looking for face or body...", statusKind = StatusKind.IDLE,
                detectionText = "Camera active", error = null, faces = emptyList(), body = null,
                periodic = periodicMode, checking = periodicMode, sleeping = false, nextCheckAtMs = 0,
                paused = false) }
        }
    }

    fun isPaused(): Boolean = synchronized(lock) { manualPaused }

    /**
     * Manual pause / resume of the timer. While paused the timer is frozen (the running session is
     * closed and saved) and no new session starts, even though detection keeps running so that
     * resuming picks up again within a second.
     */
    fun setPaused(pause: Boolean, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (!detecting || manualPaused == pause) return
            manualPaused = pause
            if (pause) {
                if (present) { endSession(now); saveTimerState(now) }
                firstSeen = 0; lastSeen = 0
                publish { it.copy(paused = true, status = "⏸ Paused — camera off, timer frozen",
                    statusKind = StatusKind.PAUSED, detectionText = "Camera paused", faces = emptyList(), body = null) }
            } else {
                firstSeen = 0; lastSeen = 0
                recentWeak.clear(); lastFrameHuman = false; lastFrameAt = 0
                publish { it.copy(paused = false, status = "🟡 Resumed — looking for face or body...",
                    statusKind = StatusKind.IDLE, detectionText = "Camera active") }
            }
        }
        Store.notifyChanged()
    }

    fun togglePause(now: Long = System.currentTimeMillis()) = setPaused(!isPaused(), now)

    /** Switches periodic mode on/off while detection keeps running. */
    fun setPeriodic(on: Boolean, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (periodic == on) return
            periodic = on
            // Continuous mode judges "frames stopped" from lastSeen: give the camera a fresh grace period.
            if (!on && present) lastSeen = now
            publish { it.copy(periodic = on, checking = on, sleeping = false, nextCheckAtMs = 0) }
        }
    }

    /** Service tells the UI whether the camera is checking, sleeping, and when it wakes up. */
    fun setCheckState(checking: Boolean, sleeping: Boolean, nextCheckAtMs: Long) {
        _live.update { it.copy(checking = checking, sleeping = sleeping, nextCheckAtMs = nextCheckAtMs) }
    }

    fun isPresent(): Boolean = synchronized(lock) { present }

    /**
     * Periodic mode: a check window ended and nobody was confirmed (or the camera could not see).
     * The countdown (timer) stops here. [reason] overrides the status text.
     */
    fun checkFoundNobody(now: Long, reason: String? = null) {
        synchronized(lock) {
            if (!detecting) return
            if (present) { endSession(now); saveTimerState(now) }
            firstSeen = 0; lastSeen = 0
            recentWeak.clear(); lastFrameHuman = false
            publish { it.copy(
                status = if (manualPaused) it.status else (reason ?: "🔴 Nobody found at the check — Timer stopped"),
                statusKind = StatusKind.PAUSED, detectionText = "Detected: None",
                faces = emptyList(), body = null) }
        }
        Store.notifyChanged()
    }

    /**
     * PC camera link dropped: stop the main countdown RIGHT NOW (no debounce). Detection stays armed,
     * so the timer starts again by itself once the link is back and a human is seen.
     */
    fun linkLost(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (!detecting) return
            if (present) { endSession(now); saveTimerState(now) }
            firstSeen = 0; lastSeen = 0
            recentWeak.clear(); lastFrameHuman = false
            publish { it.copy(
                status = if (manualPaused) it.status else "🟠 PC camera disconnected — Timer stopped, searching…",
                statusKind = if (manualPaused) it.statusKind else StatusKind.PAUSED,
                detectionText = "PC camera disconnected", faces = emptyList(), body = null) }
        }
        Store.notifyChanged()
    }

    /** PC camera link is back: keep the timer stopped until a human is actually detected again. */
    fun linkRestored() {
        synchronized(lock) {
            if (!detecting || manualPaused || present) return
            publish { it.copy(status = "🟡 PC camera connected — looking for face or body...",
                statusKind = StatusKind.IDLE, detectionText = "Camera active") }
        }
    }

    fun stopDetection(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (!detecting && !present) return
            if (present) { endSession(now); saveTimerState(now) }
            detecting = false; periodic = false; manualPaused = false
            recentWeak.clear(); personWasSeen = false
            publish { it.copy(status = "⚪ Detection stopped", statusKind = StatusKind.IDLE,
                detectionText = "Camera stopped", faces = emptyList(), body = null, alarmPlayingText = null,
                periodic = false, checking = false, sleeping = false, nextCheckAtMs = 0, paused = false) }
        }
        Store.notifyChanged()
    }

    /** Shows a short status line (used by aggressive mode while it verifies / rechecks). */
    fun setStatusText(text: String, kind: StatusKind = StatusKind.IDLE) {
        _live.update { it.copy(status = text, statusKind = kind) }
    }

    fun reportError(message: String?) {
        _live.update { it.copy(error = message) }
    }

    fun setAlarmPlaying(text: String?) {
        _live.update { it.copy(alarmPlayingText = text) }
    }

    // ---------- session helpers ----------

    private fun creditActive(now: Long) {
        if (!present || sessionStart == 0L) return
        val cursor = if (dailyCursor != 0L) dailyCursor else sessionStart
        if (now > cursor) {
            Store.addInterval(cursor, now)
            dailyCursor = now
        }
    }

    private fun endSession(now: Long) {
        if (!present) return
        creditActive(now)
        accumulated += maxOf(0L, now - sessionStart)
        present = false; sessionStart = 0; dailyCursor = 0
        firstSeen = 0; lastSeen = 0
        recentWeak.clear()
    }

    private fun saveTimerState(now: Long) {
        val running = if (present && sessionStart > 0) maxOf(0L, now - sessionStart) else 0L
        Store.saveTimer(accumulated + running, savedThrough, present, sessionStart)
    }

    // ---------- per-frame detection ----------

    /** Returns true when a human (face or body) is confirmed in this frame. */
    fun onFrame(r: FrameResult, now: Long): Boolean {
        synchronized(lock) {
            if (!detecting) return false

            val strongFace = r.faces.any { it.strong }
            val hasFace = r.faces.isNotEmpty()
            val corroborated = hasFace && r.bodyDetected
            val weak = hasFace && !strongFace && !corroborated

            recentWeak.addLast(if (weak) 1 else 0)
            while (recentWeak.size > RECENT_WINDOW) recentWeak.removeFirst()
            val weakConfirmed = weak && recentWeak.sum() >= RECENT_REQUIRED

            val faceDetected: Boolean
            val human: Boolean
            if (r.verdict != null) {
                // Aggressive mode: ONLY the multi-signal verifier decides (a chair never passes it).
                faceDetected = r.verdict.confirmed
                human = faceDetected
            } else {
                faceDetected = strongFace || corroborated || weakConfirmed
                human = faceDetected || r.bodyDetected
            }
            lastFrameAt = now
            lastFrameHuman = human

            var text: String
            var status: String? = null
            var kind: StatusKind? = null

            if (human) {
                val types = listOfNotNull(
                    if (faceDetected) "Face" else null,
                    if (r.bodyDetected) "Body" else null
                ).joinToString(" + ")
                text = "Detected: $types" +
                    if (r.bodyDetected) " • Body ${(r.bodyConfidence * 100).toInt()}%" else ""
                personWasSeen = true
                if (firstSeen == 0L) firstSeen = now
                lastSeen = now
                if (!manualPaused && !present && now - firstSeen >= START_DEBOUNCE_MS) {
                    present = true; sessionStart = now; dailyCursor = now
                    status = "🟢 Human detected — Timer running"; kind = StatusKind.RUNNING
                }
            } else {
                text = "Detected: None"
                if (!present && firstSeen != 0L && now - lastSeen >= STOP_DEBOUNCE_MS) firstSeen = 0
                // Periodic mode: the service decides at the end of the check window instead.
                if (!periodic && present && now - lastSeen >= STOP_DEBOUNCE_MS) {
                    endSession(now); saveTimerState(now)
                    status = "🔴 No face or body — Timer paused"; kind = StatusKind.PAUSED
                }
            }

            if (r.verdict != null) text = r.verdict.text
            val faceBoxes = r.faces.map { it.box }
            publish {
                it.copy(
                    detectionText = text, faces = faceBoxes, body = r.bodyBox,
                    status = status ?: it.status, statusKind = kind ?: it.statusKind, error = null
                )
            }
            return human
        }
    }

    /** True if a human was confirmed in the latest frame (and that frame is recent). */
    fun humanNow(now: Long): Boolean = synchronized(lock) { lastFrameHuman && now - lastFrameAt <= 1000 }

    /** A person is present, or detection of them is pending (grace period). */
    fun inGrace(): Boolean = synchronized(lock) { present || firstSeen != 0L }

    fun personWasSeen(): Boolean = synchronized(lock) { personWasSeen }

    /** Called ~once per second by the service: credits time, persists, and detects lost frames. */
    fun tick(now: Long) {
        checkAutoReset(now)
        synchronized(lock) {
            if (!detecting) return
            if (!periodic && present && now - lastSeen >= STOP_DEBOUNCE_MS) {
                // Frames stopped arriving (camera lost / screen off): close the session cleanly.
                endSession(now); saveTimerState(now)
                publish { it.copy(status = "🔴 No face or body — Timer paused", statusKind = StatusKind.PAUSED) }
                Store.notifyChanged()
                return
            }
            if (present) {
                creditActive(now)
                if (now - lastSaveAt >= 5000) { lastSaveAt = now; saveTimerState(now) }
                if (now - lastNotifyAt >= 5000) { lastNotifyAt = now; Store.notifyChanged() }
            }
            if (!periodic && !manualPaused && lastFrameAt != 0L && now - lastFrameAt > 6000) {
                publish { it.copy(detectionText = "No camera frames — camera may be used by another app") }
            }
            publish()
        }
    }

    // ---------- user actions ----------

    fun resetTimer(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (present && sessionStart != 0L) {
                creditActive(now)
                accumulated = 0; savedThrough = 0; sessionStart = now; dailyCursor = now
            } else {
                accumulated = 0; savedThrough = 0; sessionStart = 0; dailyCursor = 0
            }
            firstSeen = 0
            lastSeen = if (present) now else 0
            Store.clearTimer()
            saveTimerState(now)
            // Manual and automatic resets both restart the daily-reset cycle (next reset at the coming 12 AM).
            if (Store.settingsFlow.value.autoReset24h) Store.updateSettings { it.copy(autoResetAnchorMs = now) }
            publish { it.copy(
                status = if (present) "🟢 Human detected — New timer session" else "⚪ Timer reset",
                statusKind = if (present) StatusKind.RUNNING else StatusKind.IDLE) }
        }
        Store.notifyChanged()
    }

    /** Saves a record of time not yet saved. Returns a short message for the user. */
    fun saveSnapshot(now: Long = System.currentTimeMillis(), label: String = "Saved timer"): String = synchronized(lock) {
        val total = accumulated + if (present && detecting) maxOf(0L, now - sessionStart) else 0L
        val duration = total - savedThrough
        if (duration <= 0) return@synchronized "No new time to save"
        Store.addRecord(Record(
            id = "$now-${java.lang.Long.toString((Math.random() * 1e12).toLong(), 36)}",
            timestamp = now, durationMs = duration,
            dateKey = Fmt.dateKey(now), monthKey = Fmt.monthKey(now), label = label
        ))
        savedThrough = total
        saveTimerState(now)
        publish()
        "New timer time saved locally"
    }

    /**
     * Daily auto reset: when the toggle is ON and the local clock has passed 12 AM (midnight) since the last
     * reset (or since the toggle was switched on), the unsaved time is kept as a saved record and the main countdown goes back to 0.
     * Daily history is never touched. Called every second by the service and when the app opens, so a
     * reset that came due while nothing was running happens on the next start.
     */
    fun checkAutoReset(now: Long) {
        val s = Store.settingsFlow.value
        if (!s.autoReset24h) return
        if (s.autoResetAnchorMs <= 0L) {
            Store.updateSettings { it.copy(autoResetAnchorMs = now) }
            return
        }
        if (now < Fmt.nextMidnight(s.autoResetAnchorMs)) return
        synchronized(lock) {
            val again = Store.settingsFlow.value
            if (!again.autoReset24h || again.autoResetAnchorMs <= 0L ||
                now < Fmt.nextMidnight(again.autoResetAnchorMs)) return
            saveSnapshot(now, "Auto-saved (12 AM reset)")
            resetTimer(now)     // also moves the anchor to now
        }
    }

    fun resetAll() {
        synchronized(lock) {
            accumulated = 0; savedThrough = 0; sessionStart = 0; present = false
            firstSeen = 0; lastSeen = 0; dailyCursor = 0; personWasSeen = false
            recentWeak.clear()
            Store.clearAll()
            if (Store.settingsFlow.value.autoReset24h) Store.updateSettings { it.copy(autoResetAnchorMs = System.currentTimeMillis()) }
            publish { it.copy(status = "⚪ All study data reset", statusKind = StatusKind.IDLE) }
        }
    }

    /** Used by backup import: adds the backup's timer to the current one (detection must be stopped). */
    fun addImportedTimer(backupAccumulated: Long, backupSavedThrough: Long) {
        synchronized(lock) {
            val bAcc = maxOf(0L, backupAccumulated)
            val bSaved = minOf(bAcc, maxOf(0L, backupSavedThrough))
            accumulated += bAcc
            savedThrough = minOf(accumulated, savedThrough + bSaved)
            saveTimerState(System.currentTimeMillis())
            publish()
        }
    }
}
