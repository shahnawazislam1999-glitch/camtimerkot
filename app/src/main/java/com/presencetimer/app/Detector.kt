package com.presencetimer.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.hypot

/**
 * On-device face + body detection (ML Kit, models bundled in the APK -> works offline).
 * Mirrors the logic of isFaceGeometryPlausible / isBodyBoxPlausible in index.html.
 */
class PresenceAnalyzer(
    private val verifier: HumanVerifier,
    private val onResult: (FrameResult) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile var mirror = true
    private var lastProcessed = 0L

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            // Eye-open probabilities, used by the eye-closed (blink counting) mode.
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()
    )
    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
    )

    companion object {
        const val DETECTION_INTERVAL_MS = 200L
        const val BODY_LANDMARK_MIN = 0.6f        // per-landmark in-frame likelihood
        const val BODY_MIN_LANDMARKS = 8          // landmarks needed to call it a body
        const val SHOULDER_MIN = 0.5f
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val st = Store.settingsFlow.value
        val interval = when {
            st.aggressiveMode && verifier.isActive() -> HumanVerifier.FRAME_INTERVAL_MS
            // ~80 % of the frame period, so camera jitter never makes it skip every other frame
            st.eyeClosedDetect -> maxOf(8L, 800L / st.eyeFps.coerceIn(10, 60))
            else -> DETECTION_INTERVAL_MS
        }
        if (now - lastProcessed < interval) { proxy.close(); return }
        lastProcessed = now

        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        val w = if (rotation % 180 == 0) proxy.width else proxy.height
        val h = if (rotation % 180 == 0) proxy.height else proxy.width
        val flip = mirror

        try {
            val image = InputImage.fromMediaImage(media, rotation)
            // Temporary photo for the offline check (only while aggressive verification wants one).
            val photo: (() -> Bitmap?)? = if (verifier.isActive() && verifier.wantsStill(now)) {
                { try { rotateBitmap(proxy.toBitmap(), rotation) } catch (_: Throwable) { null } }
            } else null
            onResult(detect(image, w, h, flip, photo))
        } catch (e: Exception) {
            onError("Detection error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            proxy.close()
        }
    }

    /** Frames streamed from the PC webcam (already upright; never mirrored). Caller throttles. */
    fun analyzeBitmap(bmp: Bitmap) {
        try {
            val image = InputImage.fromBitmap(bmp, 0)
            val now = SystemClock.elapsedRealtime()
            val photo: (() -> Bitmap?)? = if (verifier.isActive() && verifier.wantsStill(now)) { { bmp } } else null
            onResult(detect(image, bmp.width, bmp.height, false, photo))
        } catch (e: Exception) {
            onError("Detection error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Shared by the phone camera and the PC camera: face + body detection on one image. */
    private fun detect(image: InputImage, w: Int, h: Int, flip: Boolean, photo: (() -> Bitmap?)? = null): FrameResult {
        val aggressive = Store.settingsFlow.value.aggressiveMode
        val eyeMode = Store.settingsFlow.value.eyeClosedDetect && !aggressive
        val faces = Tasks.await(faceDetector.process(image))
        val pairs = faces.mapNotNull { f -> toHit(f, w, h, flip)?.let { f to it } }
        val hits = pairs.map { it.second }

        // Eye state follows the biggest face that passes the geometry checks.
        val main = pairs.filter { it.second.strong }.maxByOrNull { it.first.boundingBox.width() * it.first.boundingBox.height() }

        val eye = if (main != null)
            EyeState.classify(main.first.leftEyeOpenProbability, main.first.rightEyeOpenProbability,
                main.first.headEulerAngleY)
        else EyeState.NONE
        val eyeProb = if (main != null)
            EyeState.openness(main.first.leftEyeOpenProbability, main.first.rightEyeOpenProbability,
                main.first.headEulerAngleY)
        else -1f

        var bodyDetected = false
        var bodyConf = 0f
        var bodyBox: NBox? = null
        var poseObs: PoseObs? = null
        var bodyRect: FloatArray? = null
        // Eye-closed mode looks at the eyes ONLY: the (expensive) body model is not used, which keeps the frame rate high.
        // Aggressive mode always needs the body model (head / hand / body movement).
        val runPose = !eyeMode
        if (runPose) try {
            val pose = Tasks.await(poseDetector.process(image))
            poseObs = toPoseObs(pose)
            val good = pose.allPoseLandmarks.filter { it.inFrameLikelihood >= BODY_LANDMARK_MIN }
            val shoulders = listOfNotNull(
                pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER),
                pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
            ).any { it.inFrameLikelihood >= SHOULDER_MIN }
            if (good.size >= BODY_MIN_LANDMARKS && shoulders) {
                val minX = good.minOf { it.position.x }.coerceIn(0f, w.toFloat())
                val maxX = good.maxOf { it.position.x }.coerceIn(0f, w.toFloat())
                val minY = good.minOf { it.position.y }.coerceIn(0f, h.toFloat())
                val maxY = good.maxOf { it.position.y }.coerceIn(0f, h.toFloat())
                val frac = ((maxX - minX) * (maxY - minY)) / (w.toFloat() * h)
                if (frac in 0.01f..0.98f) {
                    bodyDetected = true
                    bodyConf = good.map { it.inFrameLikelihood }.average().toFloat()
                    bodyBox = norm(minX, minY, maxX, maxY, w, h, flip, "BODY ${(bodyConf * 100).toInt()}%")
                    bodyRect = floatArrayOf(minX / w, minY / h, maxX / w, maxY / h)
                }
            }
        } catch (_: Exception) {
            // Pose model unavailable: face detection alone still works.
        }

        // ---- aggressive mode: the multi-signal verifier makes the decision (chair-proof) ----
        var verdict: VerifyVerdict? = null
        if (aggressive) {
            verdict = if (verifier.isActive()) {
                val mainFace: Face? = if (main != null) main.first else null
                val fo = if (mainFace != null) faceObs(mainFace, true, w, h) else null
                val roi = unionRect(bodyRect, mainFace?.boundingBox, w, h)
                val bmp = try { photo?.invoke() } catch (_: Throwable) { null }
                verifier.observe(SystemClock.elapsedRealtime(), fo, poseObs, roi, bmp)
            } else verifier.verdict()
        }
        return FrameResult(hits, bodyDetected, bodyConf, bodyBox, verdict, eye, eyeProb)
    }

    private fun faceObs(f: Face, strong: Boolean, w: Int, h: Int): FaceObs {
        val b = f.boundingBox
        val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = f.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouth = f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        var noseOk = false
        if (le != null && re != null && nose != null) {
            val eyeMidX = (le.x + re.x) / 2f; val eyeMidY = (le.y + re.y) / 2f
            val eyeDist = hypot(le.x - re.x, le.y - re.y)
            noseOk = nose.y > eyeMidY && abs(nose.x - eyeMidX) <= eyeDist * 0.7f &&
                (mouth == null || mouth.y > nose.y)
        }
        return FaceObs(
            cx = b.exactCenterX(), cy = b.exactCenterY(), w = b.width().toFloat(), h = b.height().toFloat(),
            eulerX = f.headEulerAngleX, eulerY = f.headEulerAngleY, eulerZ = f.headEulerAngleZ,
            strong = strong, hasEyes = le != null && re != null, noseOk = noseOk,
            eyeL = f.leftEyeOpenProbability, eyeR = f.rightEyeOpenProbability
        )
    }

    private fun toPoseObs(pose: com.google.mlkit.vision.pose.Pose): PoseObs {
        val m = HashMap<Int, Lm>()
        for (l in pose.allPoseLandmarks) m[l.landmarkType] = Lm(l.position.x, l.position.y, l.inFrameLikelihood)
        val ls = m[HumanVerifier.L_SHOULDER]; val rs = m[HumanVerifier.R_SHOULDER]
        val scale = if (ls != null && rs != null && ls.p >= 0.5f && rs.p >= 0.5f)
            hypot(ls.x - rs.x, ls.y - rs.y) else 0f
        return PoseObs(m, scale)
    }

    /** Normalised (l,t,r,b) box around the person: union of body box and face box. */
    private fun unionRect(body: FloatArray?, face: android.graphics.Rect?, w: Int, h: Int): FloatArray? {
        var l = 2f; var t = 2f; var r = -1f; var b = -1f
        if (body != null) { l = body[0]; t = body[1]; r = body[2]; b = body[3] }
        if (face != null) {
            l = minOf(l, face.left.toFloat() / w); t = minOf(t, face.top.toFloat() / h)
            r = maxOf(r, face.right.toFloat() / w); b = maxOf(b, face.bottom.toFloat() / h)
        }
        if (r <= l || b <= t) return null
        return floatArrayOf(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    private fun norm(l: Float, t: Float, r: Float, b: Float, w: Int, h: Int, flip: Boolean, label: String): NBox {
        val nl = l / w; val nr = r / w
        return if (flip) NBox(1f - nr, t / h, 1f - nl, b / h, label) else NBox(nl, t / h, nr, b / h, label)
    }

    /** Returns a hit only when the face passes the geometry sanity checks. */
    private fun toHit(face: Face, w: Int, h: Int, flip: Boolean): FaceHit? {
        val box = face.boundingBox
        val bw = box.width().toFloat(); val bh = box.height().toFloat()
        if (bw <= 0 || bh <= 0) return null

        val aspect = bw / bh
        if (aspect < 0.55f || aspect > 1.8f) return null
        val frac = (bw * bh) / (w.toFloat() * h)
        if (frac < 0.0008f || frac > 0.92f) return null

        val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouth = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val label = "FACE"
        val nb = norm(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), w, h, flip, label)

        // Landmarks missing: box looks like a face but is only "weak" evidence.
        if (le == null || re == null || nose == null || mouth == null) return FaceHit(nb, strong = false)

        val eyeDist = hypot(le.x - re.x, le.y - re.y)
        if (eyeDist < bw * 0.12f || eyeDist > bw * 0.95f) return null
        if (abs(le.y - re.y) > bh * 0.45f) return null
        val eyeMidY = (le.y + re.y) / 2f
        if (nose.y < eyeMidY - bh * 0.12f) return null
        if (mouth.y < nose.y - bh * 0.08f) return null
        val mx = bw * 0.35f; val my = bh * 0.35f
        for (p in listOf(le, re, nose, mouth)) {
            if (p.x < box.left - mx || p.x > box.right + mx) return null
            if (p.y < box.top - my || p.y > box.bottom + my) return null
        }
        return FaceHit(nb, strong = true)
    }

    fun close() {
        try { faceDetector.close() } catch (_: Exception) { }
        try { poseDetector.close() } catch (_: Exception) { }
    }
}

/**
 * Detects one eye blink from the per-frame "eye open" probabilities of ML Kit:
 * both eyes open -> both eyes closed (at most [MAX_CLOSED_MS]) -> both eyes open again = one blink.
 * Using hysteresis (two thresholds) and requiring BOTH eyes avoids counting winks and noisy frames.
 * A long eye closure (sleeping, squinting) is not a blink.
 */
class BlinkTracker {
    private companion object {
        const val OPEN_T = 0.6f
        const val CLOSED_T = 0.4f
        const val MAX_CLOSED_MS = 1200L
        const val MAX_GAP_MS = 600L      // longer gap between frames (camera slept / face lost) -> start over
    }

    private var state = 0                // 0 = unknown, 1 = eyes open, 2 = eyes closed
    private var closedAt = 0L
    private var lastAt = 0L

    fun reset() { state = 0; closedAt = 0L }

    /** [now] is a monotonic clock in ms. Returns true on the frame where a blink completes. */
    fun update(left: Float?, right: Float?, now: Long): Boolean {
        if (lastAt != 0L && now - lastAt > MAX_GAP_MS) reset()
        lastAt = now
        if (left == null || right == null) { reset(); return false }
        val open = minOf(left, right) >= OPEN_T
        val closed = maxOf(left, right) <= CLOSED_T
        if (open) {
            val blinked = state == 2 && now - closedAt <= MAX_CLOSED_MS
            state = 1
            return blinked
        }
        if (closed && state == 1) { state = 2; closedAt = now }
        return false
    }
}


/** Snapshot of [EyeBlinkCounter] for the service. */
data class EyeSnapshot(
    val blinks: Int,           // blinks seen since the last reset
    val faceSeen: Boolean      // a usable face was seen since the last reset
)

/**
 * Counts real eye blinks of one person (eye-closed mode). A person who keeps their eyes closed makes no blinks,
 * so "too few blinks in 2 minutes" = eyes closed.
 *
 * Built to catch very FAST blinks: a single frame that shows the eyes dipping below [CLOSE_MAX] between two
 * open frames is already one blink - no minimum closing time and no waiting after the eyes reopen.
 * Still noise-proof:
 *  - the eyes must have been open ([OPEN_MIN]) for at least [ARM_OPEN_MS] before the dip, so the flicker of
 *    permanently closed eyes cannot fake blinks,
 *  - a closure longer than [MAX_CLOSED_MS] is eyes shut, not a blink,
 *  - face lost / eyes not measurable (head turned, sunglasses) cancels the blink in progress.
 * The only limit is physics: a blink shorter than one camera frame can fall between two frames - a higher
 * frame rate (30 / 60 fps) makes that rarer.
 */
class EyeBlinkCounter {
    companion object {
        const val OPEN_MIN = 0.60f
        const val CLOSE_MAX = 0.50f      // lenient: a camera frame mid-blink often only shows a partial dip
        const val ARM_OPEN_MS = 50L
        const val MAX_CLOSED_MS = 1_000L
        const val MAX_GAP_MS = 1_000L    // no frame for this long (camera hiccup) -> blink in progress is cancelled
    }

    private val lock = Any()
    private var phase = 0                // 0 = waiting for stable open eyes, 1 = armed (open), 2 = closed
    private var openRunStart = 0L
    private var closedStart = 0L
    private var lastAt = 0L
    private var blinks = 0
    private var faceSeen = false

    fun reset() {
        synchronized(lock) {
            phase = 0; openRunStart = 0L; closedStart = 0L; lastAt = 0L
            blinks = 0; faceSeen = false
        }
    }

    /** [openness] = how open the better eye is (0..1), -1 = not measurable. [now] = System.currentTimeMillis(). */
    fun update(openness: Float, now: Long) {
        synchronized(lock) {
            if (openness >= 0f) faceSeen = true
            if (lastAt != 0L && now - lastAt > MAX_GAP_MS) { phase = 0; openRunStart = 0L }
            lastAt = now
            when {
                openness >= OPEN_MIN -> {
                    if (openRunStart == 0L) openRunStart = now
                    when (phase) {
                        0 -> if (now - openRunStart >= ARM_OPEN_MS) phase = 1
                        2 -> {
                            if (now - closedStart <= MAX_CLOSED_MS) { blinks++; phase = 1 }   // one blink, counted at once
                            else { phase = 0; openRunStart = now }
                        }
                    }
                }
                openness in 0f..CLOSE_MAX -> {
                    openRunStart = 0L
                    if (phase == 1) { phase = 2; closedStart = now }
                    if (phase == 2 && now - closedStart > MAX_CLOSED_MS) phase = 0   // too long: eyes shut, not a blink
                }
                openness >= 0f -> { /* in between: change nothing */ }
                else -> { phase = 0; openRunStart = 0L }   // no face / eyes not measurable
            }
        }
    }

    fun snapshot(): EyeSnapshot = synchronized(lock) { EyeSnapshot(blinks, faceSeen) }
}
