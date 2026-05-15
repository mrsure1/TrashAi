package app.trashai.vision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

private data class Mapped(
    val src: Detection,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
)

@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    modifier: Modifier = Modifier,
    onTap: ((Detection) -> Unit)? = null,
) {
    val detsState = rememberUpdatedState(detections)
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .let { m ->
                if (onTap == null) m else m.pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val canvasW = size.width.toFloat()
                        val canvasH = size.height.toFloat()
                        val mapped = mapAll(detsState.value, canvasW, canvasH)
                        // Smallest containing box wins (handles overlapping objects)
                        val hit = mapped
                            .filter { tap.x in it.left..it.right && tap.y in it.top..it.bottom }
                            .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                        if (hit != null) onTap(hit.src)
                    }
                }
            }
    ) {
        if (detsState.value.isEmpty()) return@Canvas
        val mapped = mapAll(detsState.value, size.width, size.height)
        for (m in mapped) {
            drawRect(
                color = NeonGreen,
                topLeft = Offset(m.left, m.top),
                size = Size(m.right - m.left, m.bottom - m.top),
                style = Stroke(width = 6f),
            )
            drawRect(
                color = NeonGreenGlow,
                topLeft = Offset(m.left - 3, m.top - 3),
                size = Size(m.right - m.left + 6, m.bottom - m.top + 6),
                style = Stroke(width = 2f),
            )
            // Inline Korean label above the box (always Korean — falls back to "기타")
            val rawLabel = m.src.topLabel?.label
            val display = if (rawLabel.isNullOrBlank()) "?" else LabelBridge.displayKo(rawLabel)
            run {
                val confSuffix = m.src.topLabel?.confidence?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                val layout = textMeasurer.measure(display + confSuffix, style = labelStyle)
                val labelX = m.left
                val labelYTop = (m.top - layout.size.height - 4f).coerceAtLeast(0f)
                drawRect(
                    color = LabelBg,
                    topLeft = Offset(labelX - 4f, labelYTop - 2f),
                    size = Size(layout.size.width + 8f, layout.size.height + 4f),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(labelX, labelYTop),
                )
            }
        }
    }
}

private fun mapAll(detections: List<Detection>, canvasW: Float, canvasH: Float): List<Mapped> {
    val out = ArrayList<Mapped>(detections.size)
    for (d in detections) {
        if (d.srcWidth <= 0 || d.srcHeight <= 0) continue
        val scale = maxOf(canvasW / d.srcWidth, canvasH / d.srcHeight)
        val dx = (canvasW - d.srcWidth * scale) / 2f
        val dy = (canvasH - d.srcHeight * scale) / 2f
        out.add(
            Mapped(
                src = d,
                left = d.bbox.left * scale + dx,
                top = d.bbox.top * scale + dy,
                right = d.bbox.right * scale + dx,
                bottom = d.bbox.bottom * scale + dy,
            )
        )
    }
    return out
}

private val NeonGreen = Color(0xFF7CFF6B)
private val NeonGreenGlow = Color(0x667CFF6B)
private val LabelBg = Color(0xCC1A4015)
private val labelStyle = TextStyle(color = Color.White, fontSize = 12.sp)
