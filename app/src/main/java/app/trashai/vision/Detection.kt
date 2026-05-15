package app.trashai.vision

import android.graphics.RectF

/**
 * One detected object in source-image coordinates.
 *
 * @param bbox bounding box in the analyzer image's coordinate space (pixels).
 * @param srcWidth width of the analyzer image (after rotation).
 * @param srcHeight height of the analyzer image (after rotation).
 * @param labels candidate labels (English, lowercased) with confidence in 0..1, sorted desc.
 */
data class Detection(
    val bbox: RectF,
    val srcWidth: Int,
    val srcHeight: Int,
    val labels: List<LabelScore>,
) {
    val topLabel: LabelScore? get() = labels.firstOrNull()
}

data class LabelScore(val label: String, val confidence: Float)
