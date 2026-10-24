package com.presencetimer.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One landmark of the body model: pixel position + how sure the model is that it is really in the picture. */
class Lm(val x: Float, val y: Float, val p: Float)

/** The face of one frame, reduced to the numbers the verifier needs (pixel coordinates). */
class FaceObs(
    val cx: Float, val cy: Float, val w: Float, val h: Float,
    val eulerX: Float, val eulerY: Float, val eulerZ: Float,
    val strong: Boolean,          // passed the geometry sanity checks (eyes / nose / mouth in believable places)
    val hasEyes: Boolean,
    val noseOk: Boolean,          // nose sits below the eye line, between the eyes, above the mouth
    val eyeL: Float?, val eyeR: Float?
)

/** The body of one frame (only landmarks the model returned). [scale] = shoulder width in px, or 0 if unknown. */
class PoseObs(val lm: Map<Int, Lm>, val scale: Float)

/** Result handed to the Engine for every frame while "aggressive" mode is on. */
data class VerifyVerdict(val confirmed: Boolean, val text: String)

/** Thread-safe copy of the verifier state for the service (main thread). */
data class VerifySnapshot(
    val active: Boolean,
    val confirmed: Boolean,
    val exhausted: Boolean,       // all slices used up and nothing was confirmed
    val firstFrameAt: Long,
    val slice: Int,
    val passed: Int
)

/**
 * "Aggressive" human verification: tells a real person from a chair / coat / poster / photo.
 *
 * A check watches the camera continuously (the service opens a 5 min window, see AppSettings.AGG_CHECK_WINDOW_MS) in
 * slices of [SLICE_MS]. At the end of every slice the last 10 seconds are judged on eight signals:
 *
 *  FACE        a face with believable geometry is seen
 *  EYES        both eyes found, eye-open values present
 *  NOSE        nose sits where a nose sits
 *  HEAD        body model sees nose / ear / eye above two shoulders (head-on-shoulders structure)
 *  HEAD MOVE   head moved or turned
 *  HAND MOVE   a hand / wrist moved
 *  BODY MOVE   shoulders / torso moved (landmarks, or pixel motion in the offline photo analysis)
 *  PHOTO       a second, more accurate face detector run offline on the temporary photos also finds a face
 *
 * A person is confirmed when a MAJORITY ([MAJORITY] of 8) passed AND at least one sign of LIFE was seen
 * (blink, eye movement, head / hand / body movement, pixel motion) AND the head structure (FACE or HEAD) is there.
 * A chair has no face, no blink, no movement of its own -> it never reaches that. A photo has a face but no life.
 *
 * Fast path: head movement (face based) or body movement (shoulder / torso based, plausible body) that lasts
 * [FAST_PATH_MS] (about 1-2 s) confirms a person immediately.
 *
 * Temporary photos: up to [MAX_STILLS] small JPEGs go to the app's private cache folder, are analysed on the
 * phone (nothing is uploaded, no network), and ALL of them are deleted as soon as the check ends.
 */
class HumanVerifier(private val dir: File) {

    companion object {
        const val SLICE_MS = 10_000L          // one judgement window
        const val SLICES = 3                  // 10 s, +10 s, +10 s = 30 s, then the camera gives up
        const val TOTAL_MS = SLICE_MS * SLICES
        const val FAST_PATH_MS = 1_200L       // movement that lasts this long = present
        const val MAJORITY = 4                // of the 8 signals
        const val FRAME_INTERVAL_MS = 100L    // analysis rate while verifying (blinks are short)
        const val STILL_EVERY_MS = 600L
        const val MAX_STILLS = 24
        const val STILL_MAX_SIDE = 480

        private const val LOOKBACK_MS = 400L          // movement = position now vs ~0.4 s ago (damps jitter)
        private const val MAX_DT_MS = 400L            // longer gaps between frames do not count as movement time
        private const val MOVE_WINDOW_MS = 3_000L     // "sustained" is measured inside this window
        private const val KEEP_MS = SLICE_MS + 3_000L

        private const val LM_P = 0.6f
        private const val MIN_RATIO = 0.4f

        // landmark ids of ML Kit PoseLandmark (kept as numbers so this class has no pose dependency)
        const val NOSE = 0
        const val L_EYE = 2
        const val R_EYE = 5
        const val L_EAR = 7
        const val R_EAR = 8
        const val L_SHOULDER = 11
        const val R_SHOULDER = 12
        const val L_ELBOW = 13
        const val R_ELBOW = 14
        const val L_WRIST = 15
        const val R_WRIST = 16
        const val L_PINKY = 17
        const val R_PINKY = 18
        const val L_INDEX = 19
        const val R_INDEX = 20
        const val L_HIP = 23
        const val R_HIP = 24

        private val HAND_IDS = intArrayOf(L_WRIST, R_WRIST, L_INDEX, R_INDEX, L_PINKY, R_PINKY)
        private val TORSO_IDS = intArrayOf(L_SHOULDER, R_SHOULDER, L_HIP, R_HIP)
    }

    enum class Sig(val label: String) {
        FACE("Face"), EYES("Eyes"), NOSE("Nose"), HEAD("Head"),
        HEAD_MOVE("Head move"), HAND_MOVE("Hand move"), BODY_MOVE("Body move"), PHOTO("Photo")
    }

    private class Obs(val t: Long, val face: FaceObs?, val pose: PoseObs?, val roi: FloatArray?) {
        var headMove = false
        var handMove = false
        var bodyMove = false
        var headOk = false          // head-on-shoulders structure in the body model
        var dt = 0L                 // time since the previous frame (capped)
    }

    private class Still(val file: File, val t: Long, val roi: FloatArray?)

    private class StillResult(val faceFound: Boolean, val pixelMotion: Boolean, val analysed: Int)

    private val lock = Any()
    private val frames = ArrayList<Obs>()
    private val blinkTracker = BlinkTracker()
    private val blinkTimes = ArrayList<Long>()
    private val stills = ArrayList<Still>()

    private var active = false
    private var continuous = false
    private var confirmed = false
    private var exhausted = false
    private var firstFrameAt = 0L
    private var slice = 0
    private var lastStillAt = 0L
    private var stillCounter = 0
    private var lastPassed = 0
    private var lastText = "Waiting for camera…"
    private var accurateFace: com.google.mlkit.vision.face.FaceDetector? = null

    init { sweep() }

    // ------------------------------------------------------------------ lifecycle

    /** Starts a fresh check. [continuous] = never gives up (used while the alarm waits for the person). */
    fun begin(continuous: Boolean = false) {
        synchronized(lock) {
            clearState()
            active = true
            this.continuous = continuous
            lastText = "🔍 Verifying human… waiting for camera"
        }
        sweep()
    }

    /** Ends the check and deletes every temporary photo. */
    fun end() {
        synchronized(lock) {
            clearState()
            active = false
        }
        sweep()
    }

    fun isActive(): Boolean = synchronized(lock) { active }

    fun snapshot(): VerifySnapshot = synchronized(lock) {
        VerifySnapshot(active, confirmed, exhausted, firstFrameAt, slice, lastPassed)
    }

    fun verdict(): VerifyVerdict = synchronized(lock) { VerifyVerdict(confirmed, lastText) }

    fun close() {
        end()
        try { accurateFace?.close() } catch (_: Exception) { }
        accurateFace = null
    }

    private fun clearState() {
        frames.clear(); blinkTimes.clear(); blinkTracker.reset()
        stills.clear()
        confirmed = false; exhausted = false
        firstFrameAt = 0L; slice = 0; lastStillAt = 0L; stillCounter = 0; lastPassed = 0
    }

    /** Deletes every file in the temporary photo folder. */
    fun sweep() {
        try {
            dir.listFiles()?.forEach { f -> try { f.delete() } catch (_: Exception) { } }
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ per frame

    /** True when the next frame should also be saved as a temporary photo. */
    fun wantsStill(now: Long): Boolean = synchronized(lock) {
        active && !confirmed && stills.size < MAX_STILLS && now - lastStillAt >= STILL_EVERY_MS
    }

    /**
     * Feeds one analysed frame. [roi] = normalised box (l,t,r,b) around the person, [photo] = upright bitmap or null.
     * Runs on the analysis thread. Returns the verdict for the Engine.
     */
    fun observe(now: Long, face: FaceObs?, pose: PoseObs?, roi: FloatArray?, photo: Bitmap?): VerifyVerdict {
        synchronized(lock) {
            if (!active) return VerifyVerdict(confirmed, lastText)
            if (confirmed) return VerifyVerdict(true, lastText)
            if (firstFrameAt == 0L) firstFrameAt = now

            val obs = Obs(now, face, pose, roi)
            obs.dt = if (frames.isEmpty()) 0L else min(MAX_DT_MS, now - frames.last().t)
            obs.headOk = headStructure(pose, face)
            computeMovement(obs)
            frames.add(obs)
            prune(now)

            // blinks (needs the biggest strong face; reset when the face is gone)
            if (face != null && face.strong) {
                if (blinkTracker.update(face.eyeL, face.eyeR, now)) blinkTimes.add(now)
            } else blinkTracker.reset()

            if (photo != null) saveStill(photo, now, roi)

            // 1) fast path: 1-2 s of real movement
            val headMs = movingMs(now) { it.headMove }
            val bodyMs = movingMs(now) { it.bodyMove }
            val plausibleBody = frames.count { now - it.t <= MOVE_WINDOW_MS && it.headOk } >= 3
            if (headMs >= FAST_PATH_MS && face != null && face.strong) {
                return confirm("✅ Head movement ${headMs / 100 / 10.0}s – human")
            }
            if (bodyMs >= FAST_PATH_MS && plausibleBody) {
                return confirm("✅ Body movement ${bodyMs / 100 / 10.0}s – human")
            }

            // 2) live signals for the status text
            val live = evaluateLive(now, SLICE_MS)
            lastPassed = live.count { it.value }
            val secs = (now - firstFrameAt) / 1000
            lastText = "🔍 Verifying ${secs}s • ${signalText(live)}"

            // 3) end of a slice: majority vote (with the offline photo analysis)
            val sliceEnd = firstFrameAt + (slice + 1) * SLICE_MS
            if (now >= sliceEnd) {
                val st = analyseStills(now)
                val sig = evaluateLive(now, SLICE_MS).toMutableMap()
                sig[Sig.PHOTO] = st.faceFound
                if (st.pixelMotion) sig[Sig.BODY_MOVE] = true
                lastPassed = sig.count { it.value }
                val life = hasLife(now, sig, st)
                val core = sig[Sig.FACE] == true || sig[Sig.HEAD] == true
                val ok = lastPassed >= MAJORITY && life && core
                lastText = "${if (ok) "✅" else "⚠️"} Check ${slice + 1}/$SLICES • $lastPassed/8 • ${signalText(sig)}" +
                    if (!life) " • no life sign" else ""
                if (ok) return confirm(lastText)
                slice++
                clearStills()           // photos of a failed slice are not needed any more
                if (!continuous && slice >= SLICES) exhausted = true
            }
            return VerifyVerdict(false, lastText)
        }
    }

    private fun confirm(text: String): VerifyVerdict {
        confirmed = true
        lastText = text
        clearStills()                   // human confirmed -> delete the temporary photos right away
        return VerifyVerdict(true, text)
    }

    private fun prune(now: Long) {
        while (frames.isNotEmpty() && now - frames.first().t > KEEP_MS) frames.removeAt(0)
        while (blinkTimes.isNotEmpty() && now - blinkTimes.first() > KEEP_MS) blinkTimes.removeAt(0)
    }

    // ------------------------------------------------------------------ movement

    /** Compares this frame with the newest frame that is at least [LOOKBACK_MS] older. */
    private fun computeMovement(cur: Obs) {
        var prev: Obs? = null
        for (i in frames.indices.reversed()) {
            if (cur.t - frames[i].t >= LOOKBACK_MS) { prev = frames[i]; break }
        }
        if (prev == null) return
        val cf = cur.face; val pf = prev.face
        if (cf != null && pf != null && cf.strong && pf.strong && cf.w > 0f) {
            val shift = hypot(cf.cx - pf.cx, cf.cy - pf.cy) / cf.w
            val ang = max(abs(cf.eulerX - pf.eulerX), max(abs(cf.eulerY - pf.eulerY), abs(cf.eulerZ - pf.eulerZ)))
            val grow = abs(cf.w - pf.w) / cf.w
            cur.headMove = shift >= 0.07f || ang >= 6f || grow >= 0.10f
        }
        val cp = cur.pose; val pp = prev.pose
        if (cp != null && pp != null) {
            val scale = refScale(cp, pp, cf)
            if (scale > 0f) {
                // hands
                for (id in HAND_IDS) {
                    val a = cp.lm[id]; val b = pp.lm[id]
                    if (a != null && b != null && a.p >= LM_P + 0.05f && b.p >= LM_P + 0.05f &&
                        hypot(a.x - b.x, a.y - b.y) >= 0.12f * scale) { cur.handMove = true; break }
                }
                // torso: average shift of the shoulder / hip points both frames share
                var sum = 0f; var n = 0
                for (id in TORSO_IDS) {
                    val a = cp.lm[id]; val b = pp.lm[id]
                    if (a != null && b != null && a.p >= 0.5f && b.p >= 0.5f) {
                        sum += hypot(a.x - b.x, a.y - b.y); n++
                    }
                }
                if (n >= 2 && sum / n >= 0.08f * scale) cur.bodyMove = true
            }
        }
    }

    private fun refScale(a: PoseObs, b: PoseObs, face: FaceObs?): Float = when {
        a.scale > 0f -> a.scale
        b.scale > 0f -> b.scale
        face != null -> face.w * 2.2f
        else -> 0f
    }

    /** Milliseconds of movement inside the last [MOVE_WINDOW_MS]. */
    private fun movingMs(now: Long, flag: (Obs) -> Boolean): Long {
        var ms = 0L
        for (o in frames) if (now - o.t <= MOVE_WINDOW_MS && flag(o)) ms += o.dt
        return ms
    }

    private fun movingMsWindow(now: Long, windowMs: Long, flag: (Obs) -> Boolean): Long {
        var ms = 0L
        for (o in frames) if (now - o.t <= windowMs && flag(o)) ms += o.dt
        return ms
    }

    // ------------------------------------------------------------------ signals

    /** Head-on-shoulders structure in the body model (nose + ear/eye above two shoulders). */
    private fun headStructure(pose: PoseObs?, face: FaceObs?): Boolean {
        if (pose == null) return false
        val nose = pose.lm[NOSE]
        val ls = pose.lm[L_SHOULDER]; val rs = pose.lm[R_SHOULDER]
        if (nose == null || ls == null || rs == null) return false
        if (nose.p < 0.5f || ls.p < 0.4f || rs.p < 0.4f) return false
        val sw = hypot(ls.x - rs.x, ls.y - rs.y)
        if (sw < 8f) return false
        val shMidX = (ls.x + rs.x) / 2f; val shMidY = (ls.y + rs.y) / 2f
        if (nose.y >= shMidY) return false                       // head must be above the shoulders
        if (abs(nose.x - shMidX) > sw * 0.9f) return false       // and roughly over them
        val sides = listOf(L_EAR, R_EAR, L_EYE, R_EYE).count { (pose.lm[it]?.p ?: 0f) >= 0.4f }
        if (sides < 1) return false
        // if a face box exists it has to sit on the body's head
        if (face != null && hypot(face.cx - nose.x, face.cy - nose.y) > max(face.w, sw)) return false
        return true
    }

    /** The first seven signals, judged on the last [windowMs]. PHOTO is added by the caller. */
    private fun evaluateLive(now: Long, windowMs: Long): Map<Sig, Boolean> {
        val win = frames.filter { now - it.t <= windowMs }
        val n = win.size
        val out = LinkedHashMap<Sig, Boolean>()
        fun ratio(f: (Obs) -> Boolean): Float = if (n == 0) 0f else win.count(f).toFloat() / n
        val enough = n >= 4
        out[Sig.FACE] = enough && ratio { it.face?.strong == true } >= MIN_RATIO
        out[Sig.EYES] = enough && ratio { o -> val f = o.face; f != null && f.hasEyes && f.eyeL != null && f.eyeR != null } >= MIN_RATIO
        out[Sig.NOSE] = enough && ratio { it.face?.noseOk == true } >= MIN_RATIO
        out[Sig.HEAD] = enough && ratio { it.headOk } >= MIN_RATIO
        out[Sig.HEAD_MOVE] = movingMsWindow(now, windowMs) { it.headMove } >= 600L
        out[Sig.HAND_MOVE] = movingMsWindow(now, windowMs) { it.handMove } >= 500L
        out[Sig.BODY_MOVE] = movingMsWindow(now, windowMs) { it.bodyMove } >= 800L
        out[Sig.PHOTO] = false
        return out
    }

    /** Any sign that the thing in front of the camera is alive. */
    private fun hasLife(now: Long, sig: Map<Sig, Boolean>, st: StillResult): Boolean {
        if (blinkTimes.any { now - it <= SLICE_MS }) return true
        if (sig[Sig.HEAD_MOVE] == true || sig[Sig.HAND_MOVE] == true || sig[Sig.BODY_MOVE] == true) return true
        if (st.pixelMotion) return true
        // eyes opening / closing without a clean blink (probability swings)
        val probs = frames.filter { now - it.t <= SLICE_MS }
            .mapNotNull { o -> o.face?.takeIf { it.strong }?.let { minOf(it.eyeL ?: 1f, it.eyeR ?: 1f) } }
        if (probs.size >= 5 && (probs.max() - probs.min()) >= 0.35f) return true
        return false
    }

    private fun signalText(sig: Map<Sig, Boolean>): String =
        Sig.values().joinToString(" ") { (if (sig[it] == true) "✓" else "✗") + it.label }

    // ------------------------------------------------------------------ temporary photos (offline analysis)

    private fun saveStill(bmp: Bitmap, now: Long, roi: FloatArray?) {
        lastStillAt = now
        try {
            if (!dir.exists()) dir.mkdirs()
            val side = max(bmp.width, bmp.height)
            val small = if (side > STILL_MAX_SIDE) {
                val k = STILL_MAX_SIDE.toFloat() / side
                Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true)
            } else bmp
            val f = File(dir, "v_${stillCounter++}.jpg")
            FileOutputStream(f).use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            if (small !== bmp) small.recycle()
            stills.add(Still(f, now, roi))
        } catch (_: Exception) { }
    }

    private fun clearStills() {
        for (s in stills) try { s.file.delete() } catch (_: Exception) { }
        stills.clear()
        sweep()
    }

    private fun accurate(): com.google.mlkit.vision.face.FaceDetector {
        var d = accurateFace
        if (d == null) {
            d = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setMinFaceSize(0.08f)
                    .build()
            )
            accurateFace = d
        }
        return d!!
    }

    /**
     * Offline analysis of the temporary photos: (a) the ACCURATE face detector looks at up to 3 of them,
     * (b) pixel motion between neighbouring photos inside the person's area compared with the background
     * (a chair or poster does not move; global exposure changes are removed by the background term).
     */
    private fun analyseStills(now: Long): StillResult {
        val list = stills.filter { now - it.t <= SLICE_MS + 1_000L && it.file.exists() }
        if (list.isEmpty()) return StillResult(false, false, 0)

        // (a) accurate face on up to 3 evenly spaced photos
        var faceFound = false
        val picks = if (list.size <= 3) list else listOf(list.first(), list[list.size / 2], list.last())
        for (s in picks) {
            val bmp = try { BitmapFactory.decodeFile(s.file.absolutePath) } catch (_: Exception) { null } ?: continue
            try {
                val faces = Tasks.await(accurate().process(InputImage.fromBitmap(bmp, 0)))
                for (f in faces) {
                    val b = f.boundingBox
                    val bw = b.width().toFloat(); val bh = b.height().toFloat()
                    if (bw <= 0f || bh <= 0f) continue
                    val aspect = bw / bh
                    if (aspect < 0.55f || aspect > 1.8f) continue
                    val le = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
                    val re = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
                    val nose = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE)?.position
                    val mouth = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM)?.position
                    if (le == null || re == null || nose == null || mouth == null) continue
                    val eyeMidY = (le.y + re.y) / 2f
                    if (nose.y <= eyeMidY || mouth.y <= nose.y) continue
                    if (hypot(le.x - re.x, le.y - re.y) < bw * 0.2f) continue
                    faceFound = true
                }
            } catch (_: Exception) {
            } finally {
                bmp.recycle()
            }
            if (faceFound) break
        }

        // (b) pixel motion between neighbouring photos
        var okPairs = 0
        var prevGray: IntArray? = null
        for (s in list) {
            val g = grayGrid(s.file) ?: continue
            val pg = prevGray
            if (pg != null) {
                val roi = s.roi
                var rs = 0L; var rn = 0; var bs = 0L; var bn = 0
                for (y in 0 until GRID_H) for (x in 0 until GRID_W) {
                    val d = abs(g[y * GRID_W + x] - pg[y * GRID_W + x])
                    val inside = roi != null &&
                        (x + 0.5f) / GRID_W >= roi[0] && (x + 0.5f) / GRID_W <= roi[2] &&
                        (y + 0.5f) / GRID_H >= roi[1] && (y + 0.5f) / GRID_H <= roi[3]
                    if (inside) { rs += d; rn++ } else { bs += d; bn++ }
                }
                if (rn >= 20) {
                    val roiMean = rs.toFloat() / rn
                    val bgMean = if (bn >= 60) bs.toFloat() / bn else 0f
                    if (roiMean >= 1.5f && roiMean - bgMean >= 0.8f) okPairs++
                }
            }
            prevGray = g
        }
        return StillResult(faceFound, okPairs >= 2, list.size)
    }

    private val GRID_W = 40
    private val GRID_H = 30

    /** Loads a photo and reduces it to a 40x30 grey grid (the averaging also acts as a blur against sensor noise). */
    private fun grayGrid(f: File): IntArray? {
        val bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null } ?: return null
        try {
            val small = Bitmap.createScaledBitmap(bmp, GRID_W, GRID_H, true)
            val px = IntArray(GRID_W * GRID_H)
            small.getPixels(px, 0, GRID_W, 0, 0, GRID_W, GRID_H)
            if (small !== bmp) small.recycle()
            return IntArray(px.size) { i ->
                val c = px[i]
                (((c shr 16) and 0xFF) * 30 + ((c shr 8) and 0xFF) * 59 + (c and 0xFF) * 11) / 100
            }
        } catch (_: Exception) {
            return null
        } finally {
            bmp.recycle()
        }
    }
}

/** Rotates [src] by [degrees] (used to make camera frames upright). */
fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return src
    val m = Matrix()
    m.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}
