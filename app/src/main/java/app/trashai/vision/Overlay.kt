package app.trashai.vision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

private data class Mapped(
    val src: Detection,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
)

/**
 * Single overlay handling **both** "tap a detection box" and
 * "drag to draw a custom region" inside one pointerInput so the gesture
 * detectors don't fight each other (the previous two-overlay layout had the
 * drag handler eating all events before tap could fire).
 *
 * Gesture arbitration:
 *   - Down → wait for touch slop
 *   - Slop NOT crossed (released early) → quick tap → hit-test boxes
 *   - Slop crossed → drag → draw rectangle, show confirm/cancel
 */
@Composable
fun GestureOverlay(
    detections: List<Detection>,
    onBoxTap: (Detection) -> Unit,
    onRegionConfirmed: (rectInScreenPx: Rect, canvasSize: IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val detsState = rememberUpdatedState(detections)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                canvasSize = IntSize(size.width, size.height)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position

                    // Distinguish tap vs drag by touch slop.
                    val slop = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                    }

                    if (slop == null) {
                        // Tap. Hit-test detection boxes; smallest containing wins.
                        val canvasW = size.width.toFloat()
                        val canvasH = size.height.toFloat()
                        val mapped = mapAll(detsState.value, canvasW, canvasH)
                        val hit = mapped
                            .filter { downPos.x in it.left..it.right && downPos.y in it.top..it.bottom }
                            .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                        if (hit != null) {
                            // Clear any stale drag rect on a successful box tap
                            dragStart = null; dragEnd = null
                            onBoxTap(hit.src)
                        }
                    } else {
                        // Drag. Track until release to draw a custom region.
                        dragStart = downPos
                        dragEnd = slop.position
                        drag(slop.id) { change ->
                            dragEnd = change.position
                            change.consume()
                        }
                        // dragStart/dragEnd persist for confirm/cancel UI below
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Detection boxes (auto-detected)
            val mapped = mapAll(detsState.value, size.width, size.height)
            for (m in mapped) {
                drawNeonBrackets(this, m.left, m.top, m.right, m.bottom)
            }
            // User drag rectangle
            val s = dragStart; val e = dragEnd
            if (s != null && e != null) {
                val left = minOf(s.x, e.x); val top = minOf(s.y, e.y)
                val right = maxOf(s.x, e.x); val bottom = maxOf(s.y, e.y)
                drawNeonBrackets(this, left, top, right, bottom)
            }
        }

        // Confirm/cancel for the drag rect
        val s = dragStart; val e = dragEnd
        if (s != null && e != null) {
            val left = minOf(s.x, e.x); val top = minOf(s.y, e.y)
            val right = maxOf(s.x, e.x); val bottom = maxOf(s.y, e.y)
            val rect = Rect(left, top, right, bottom)
            if (rect.width > 32 && rect.height > 32) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (canvasSize.width > 0 && canvasSize.height > 0) {
                                onRegionConfirmed(rect, canvasSize)
                                dragStart = null; dragEnd = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D5A27)),
                    ) { Text("이 영역 분석") }
                    OutlinedButton(
                        onClick = { dragStart = null; dragEnd = null },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Text("취소") }
                }
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

private fun drawNeonBrackets(
    drawScope: DrawScope,
    left: Float, top: Float, right: Float, bottom: Float
) {
    val length = minOf(60f, (right - left) / 3f, (bottom - top) / 3f).coerceAtLeast(10f)
    val radius = minOf(24f, length)

    val path = Path()
    // Top-Left
    path.moveTo(left, top + length)
    path.lineTo(left, top + radius)
    path.quadraticBezierTo(left, top, left + radius, top)
    path.lineTo(left + length, top)

    // Top-Right
    path.moveTo(right - length, top)
    path.lineTo(right - radius, top)
    path.quadraticBezierTo(right, top, right, top + radius)
    path.lineTo(right, top + length)

    // Bottom-Right
    path.moveTo(right, bottom - length)
    path.lineTo(right, bottom - radius)
    path.quadraticBezierTo(right, bottom, right - radius, bottom)
    path.lineTo(right - length, bottom)

    // Bottom-Left
    path.moveTo(left + length, bottom)
    path.lineTo(left + radius, bottom)
    path.quadraticBezierTo(left, bottom, left, bottom - radius)
    path.lineTo(left, bottom - length)

    drawScope.apply {
        // 전체 박스는 너무 희미하지 않게 어느정도 가시성 확보
        drawRoundRect(
            color = NeonGreen.copy(alpha = 0.5f),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = 5f)
        )
        // 코너 부분 네온 효과 (두꺼운 반투명 선)
        drawPath(
            path = path,
            color = NeonGreen.copy(alpha = 0.6f),
            style = Stroke(width = 20f, cap = StrokeCap.Round)
        )
        // 코너 부분 중앙 화이트 하이라이트 (굵은 라인)
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(width = 8f, cap = StrokeCap.Round)
        )
    }
}

private val NeonGreen = Color(0xFF7CFF6B)
private val NeonGreenGlow = Color(0x667CFF6B)
