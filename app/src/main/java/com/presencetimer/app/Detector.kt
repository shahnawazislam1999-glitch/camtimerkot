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
    private val onResult: (FrameResult) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile var mirror = true
    private var lastProcessed = 0L

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
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
        if (now - lastProcessed < DETECTION_INTERVAL_MS) { proxy.close(); return }
        lastProcessed = now

        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        val w = if (rotation % 180 == 0) proxy.width else proxy.height
        val h = if (rotation % 180 == 0) proxy.height else proxy.width
        val flip = mirror

        try {
            val image = InputImage.fromMediaImage(media, rotation)
            onResult(detect(image, w, h, flip))
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
            onResult(detect(image, bmp.width, bmp.height, false))
        } catch (e: Exception) {
            onError("Detection error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Shared by the phone camera and the PC camera: face + body detection on one image. */
    private fun detect(image: InputImage, w: Int, h: Int, flip: Boolean): FrameResult {
        val faces = Tasks.await(faceDetector.process(image))
        val hits = faces.mapNotNull { toHit(it, w, h, flip) }

        var bodyDetected = false
        var bodyConf = 0f
        var bodyBox: NBox? = null
        try {
            val pose = Tasks.await(poseDetector.process(image))
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
                }
            }
        } catch (_: Exception) {
            // Pose model unavailable: face detection alone still works.
        }

        return FrameResult(hits, bodyDetected, bodyConf, bodyBox)
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
