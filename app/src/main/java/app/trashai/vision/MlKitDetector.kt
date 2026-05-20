package app.trashai.vision

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.tasks.await

/**
 * Returns generic bounding boxes for any visible salient object — no
 * classification. Intentional: ML Kit's coarse 5-class classifier was the
 * source of bad labels in earlier iterations. Identification of a tapped box
 * is delegated to Gemini, which is far more accurate.
 *
 * The analyze() result also carries the rotated source bitmap so the camera
 * screen can crop *the exact frame the box was detected on* when the user
 * taps — no extra ImageCapture round-trip needed.
 */
class MlKitDetector : AutoCloseable {

    private val detector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification() // 분류 기능 활성화
            .build()
    )

    private data class TrackedObject(
        val id: Int,
        var bbox: RectF,
        var lastSeenMs: Long
    )

    private var nextVirtualId = 10000 // ML Kit 진짜 ID와 겹치지 않게 큰 값부터 시작
    private val activeTracks = mutableListOf<TrackedObject>()
    private val trackTimeoutMs = 1500L

    @SuppressLint("UnsafeOptInUsageError")
    suspend fun analyze(image: ImageProxy): DetectionResult? {
        val media = image.image ?: return null
        val rotation = image.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)

        // Snapshot the rotated frame for later cropping on tap.
        val raw = runCatching { image.toBitmap() }.getOrNull() ?: return null
        val rotated = if (rotation == 0) raw else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        }
        val srcW = rotated.width
        val srcH = rotated.height

        val objects = detector.process(input).await()
        val now = System.currentTimeMillis()

        // 1. 오래된 가상 트랙 타임아웃 제거
        activeTracks.removeAll { now - it.lastSeenMs > trackTimeoutMs }

        val boxes = objects.map { obj ->
            val r = obj.boundingBox
            val bboxF = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
            
            // 2. ML Kit 트래킹 ID 획득 시도
            var finalTid = obj.trackingId
            
            if (finalTid == null) {
                // 3. 트래킹 ID 누락 시 로컬 중심점 기반 소프트 트래킹 적용
                val matched = activeTracks.minByOrNull { track ->
                    val dx = track.bbox.centerX() - bboxF.centerX()
                    val dy = track.bbox.centerY() - bboxF.centerY()
                    dx * dx + dy * dy
                }
                
                if (matched != null) {
                    val dx = matched.bbox.centerX() - bboxF.centerX()
                    val dy = matched.bbox.centerY() - bboxF.centerY()
                    val distSq = dx * dx + dy * dy
                    
                    // 프레임 간 150픽셀 이내 근접 시 동일 사물로 판정
                    if (distSq < 150f * 150f) {
                        matched.bbox = bboxF
                        matched.lastSeenMs = now
                        finalTid = matched.id
                    }
                }
                
                if (finalTid == null) {
                    val newId = nextVirtualId++
                    activeTracks.add(TrackedObject(newId, bboxF, now))
                    finalTid = newId
                }
            } else {
                // ML Kit ID가 넘어온 경우 동기화
                val existing = activeTracks.find { it.id == finalTid }
                if (existing != null) {
                    existing.bbox = bboxF
                    existing.lastSeenMs = now
                } else {
                    activeTracks.add(TrackedObject(finalTid, bboxF, now))
                }
            }

            val rawLabel = obj.labels.firstOrNull()?.text
            Detection(
                bbox = bboxF,
                srcWidth = srcW,
                srcHeight = srcH,
                trackingId = finalTid,
                rawMlKitLabel = rawLabel,
            )
        }
        return DetectionResult(boxes, rotated)
    }

    override fun close() { detector.close() }
}
