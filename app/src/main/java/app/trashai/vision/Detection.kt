package app.trashai.vision

import android.graphics.Bitmap
import android.graphics.RectF

/** A single bounding box from on-device detection (no label — Gemini provides those on tap). */
data class Detection(
    val bbox: RectF,        // pixels in source-image coords
    val srcWidth: Int,
    val srcHeight: Int,
)

/** Snapshot returned per frame: boxes + the rotated bitmap they were detected on. */
data class DetectionResult(
    val detections: List<Detection>,
    val sourceBitmap: Bitmap,
)
