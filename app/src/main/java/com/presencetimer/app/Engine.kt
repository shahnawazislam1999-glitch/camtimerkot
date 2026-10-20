package com.presencetimer.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class StatusKind { IDLE, RUNNING, PAUSED }

/** Box in normalized (0..1) coordinates of the upright, already-mirrored preview. */
data class NBox(val l: Float, val t: Float, val r: Float, val b: Float, val label: String)

data class FaceHit(val box: NBox, val strong: Boolean)

data class FrameResult(
    val faces: List<FaceHit>,
    val bodyDetected: Boolean,
    val bodyConfidence: Float,
    val bodyBox: NBox?
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
    val error: String? = null
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

    fun startDetection() {
        synchronized(lock) {
            detecting = true; present = false
            firstSeen = 0; lastSeen = 0; sessionStart = 0; dailyCursor = 0
            recentWeak.clear(); personWasSeen = false
            lastFrameAt = 0; lastFrameHuman = false
            publish { it.copy(status = "🟡 Looking for face or body...", statusKind = StatusKind.IDLE,
                detectionText = "Camera active", error = null, faces = emptyList(), body = null) }
        }
    }

    fun stopDetection(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (!detecting && !present) return
            if (present) { endSession(now); saveTimerState(now) }
            detecting = false
            recentWeak.clear(); personWasSeen = false
            publish { it.copy(status = "⚪ Detection stopped", statusKind = StatusKind.IDLE,
                detectionText = "Camera stopped", faces = emptyList(), body = null, alarmPlayingText = null) }
        }
        Store.notifyChanged()
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

            val faceDetected = strongFace || corroborated || weakConfirmed
            val human = faceDetected || r.bodyDetected
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
                if (!present && now - firstSeen >= START_DEBOUNCE_MS) {
                    present = true; sessionStart = now; dailyCursor = now
                    status = "🟢 Human detected — Timer running"; kind = StatusKind.RUNNING
                }
            } else {
                text = "Detected: None"
                if (!present && firstSeen != 0L && now - lastSeen >= STOP_DEBOUNCE_MS) firstSeen = 0
                if (present && now - lastSeen >= STOP_DEBOUNCE_MS) {
                    endSession(now); saveTimerState(now)
                    status = "🔴 No face or body — Timer paused"; kind = StatusKind.PAUSED
                }
            }

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
        synchronized(lock) {
            if (!detecting) return
            if (present && now - lastSeen >= STOP_DEBOUNCE_MS) {
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
            if (lastFrameAt != 0L && now - lastFrameAt > 6000) {
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
            publish { it.copy(
                status = if (present) "🟢 Human detected — New timer session" else "⚪ Timer reset",
                statusKind = if (present) StatusKind.RUNNING else StatusKind.IDLE) }
        }
        Store.notifyChanged()
    }

    /** Saves a record of time not yet saved. Returns a short message for the user. */
    fun saveSnapshot(now: Long = System.currentTimeMillis()): String = synchronized(lock) {
        val total = accumulated + if (present && detecting) maxOf(0L, now - sessionStart) else 0L
        val duration = total - savedThrough
        if (duration <= 0) return@synchronized "No new time to save"
        Store.addRecord(Record(
            id = "$now-${java.lang.Long.toString((Math.random() * 1e12).toLong(), 36)}",
            timestamp = now, durationMs = duration,
            dateKey = Fmt.dateKey(now), monthKey = Fmt.monthKey(now), label = "Saved timer"
        ))
        savedThrough = total
        saveTimerState(now)
        publish()
        "New timer time saved locally"
    }

    fun resetAll() {
        synchronized(lock) {
            accumulated = 0; savedThrough = 0; sessionStart = 0; present = false
            firstSeen = 0; lastSeen = 0; dailyCursor = 0; personWasSeen = false
            recentWeak.clear()
            Store.clearAll()
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
