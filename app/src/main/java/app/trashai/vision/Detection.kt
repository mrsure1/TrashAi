package app.trashai.vision

import android.graphics.Bitmap
import android.graphics.RectF

/** A single bounding box from on-device detection (no label — Gemini provides those on tap). */
data class Detection(
    val bbox: RectF,        // pixels in source-image coords
    val srcWidth: Int,
    val srcHeight: Int,
    val label: String? = null, // 분석 결과 매칭된 사물 명칭 (예: "페트병")
    val trackingId: Int? = null, // ML Kit STREAM_MODE 사물 트래킹 ID
    val rawMlKitLabel: String? = null, // ML Kit 온디바이스 사물 분류 레이블
)

/** Snapshot returned per frame: boxes + the rotated bitmap they were detected on. */
data class DetectionResult(
    val detections: List<Detection>,
    val sourceBitmap: Bitmap,
)
