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
            .enableObjectTracking()
            .build()
    )

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
        val boxes = objects.map { obj ->
            val r = obj.boundingBox
            Detection(
                bbox = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()),
                srcWidth = srcW,
                srcHeight = srcH,
                trackingId = obj.trackingId,
            )
        }
        return DetectionResult(boxes, rotated)
    }

    override fun close() { detector.close() }
}
