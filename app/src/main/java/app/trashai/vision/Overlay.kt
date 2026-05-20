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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.layout.onSizeChanged
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
    onDrawingStateChange: (Boolean) -> Unit = {},
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val detsState = rememberUpdatedState(detections)

    LaunchedEffect(dragStart, dragEnd) {
        onDrawingStateChange(dragStart != null && dragEnd != null)
    }

    // 최상위 컨테이너 (여기에 pointerInput을 달지 않습니다! 레이어를 분리하기 위함)
    Box(modifier = modifier.fillMaxSize()) {
        // 1. 하위 레이어: 제스처 감지 및 캔버스 드로잉 전용 Box
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // 상위 레이어의 버튼이 터치 이벤트를 소비했으면 무시하도록 requireUnconsumed = true로 설정합니다.
                        val down = awaitFirstDown(requireUnconsumed = true)
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
                            } else {
                                // 빈 배경을 탭한 경우, 기존에 드래그된 커스텀 영역이 있다면 취소(초기화)합니다.
                                if (dragStart != null || dragEnd != null) {
                                    dragStart = null; dragEnd = null
                                }
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
                            // 드래그가 종료된 후, 생성된 박스의 크기가 너무 작으면(32px 이하) 오터치/실수로 간주하여 자동 취소합니다.
                            val s = dragStart; val e = dragEnd
                            if (s != null && e != null) {
                                val w = kotlin.math.abs(e.x - s.x)
                                val h = kotlin.math.abs(e.y - s.y)
                                if (w <= 32f || h <= 32f) {
                                    dragStart = null; dragEnd = null
                                }
                            }
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Detection boxes (auto-detected) - 녹색 바운딩 박스
                val mapped = mapAll(detsState.value, size.width, size.height)
                for (m in mapped) {
                    drawNeonBrackets(this, m.left, m.top, m.right, m.bottom, isCustomDrag = false)
                }
                // User drag rectangle - 사용자가 직접 드래그한 영역은 주황색 네온 바운딩 박스
                val s = dragStart; val e = dragEnd
                if (s != null && e != null) {
                    val left = minOf(s.x, e.x); val top = minOf(s.y, e.y)
                    val right = maxOf(s.x, e.x); val bottom = maxOf(s.y, e.y)
                    drawNeonBrackets(this, left, top, right, bottom, isCustomDrag = true)
                }
            }

            // 1-1. 실시간 사물 감지(초록색) 라벨 칩 오버레이
            val density = LocalDensity.current.density
            val mapped = mapAll(detsState.value, canvasSize.width.toFloat(), canvasSize.height.toFloat())
            for (m in mapped) {
                if (!m.src.label.isNullOrEmpty()) {
                    Box(
                        modifier = Modifier
                            .offset(
                                x = (m.left / density).dp,
                                y = ((m.top - 32f).coerceAtLeast(0f) / density).dp
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xE6000000))
                            .border(
                                width = 1.dp,
                                color = NeonGreen,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = m.src.label,
                            color = NeonGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 1-2. 사용자가 직접 드래그한 주황색 영역 가이드 칩 오버레이
            val s = dragStart; val e = dragEnd
            if (s != null && e != null) {
                val left = minOf(s.x, e.x)
                val top = minOf(s.y, e.y)
                Box(
                    modifier = Modifier
                        .offset(
                            x = (left / density).dp,
                            y = ((top - 32f).coerceAtLeast(0f) / density).dp
                        )
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xE6000000))
                        .border(
                            width = 1.dp,
                            color = NeonOrange,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "드래그 선택 영역",
                        color = NeonOrange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 2. 상위 레이어 (최상단 Layer): Confirm/cancel 팝업 메뉴
        // 하위 레이어(제스처 Box)와 완전히 독립된 형제(Sibling)로서 위에 덮어씌워지며,
        // 버튼 클릭 시 터치 이벤트가 하위 레이어로 전파되지 않고 100% 소비됩니다.
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)), // 주황색 네온과 어울리는 다크 오렌지
                    ) { Text("이 영역 분석", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
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
    left: Float, top: Float, right: Float, bottom: Float,
    isCustomDrag: Boolean = false
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

    val primaryColor = if (isCustomDrag) NeonOrange else NeonGreen

    drawScope.apply {
        // 전체 박스는 너무 희미하지 않게 어느정도 가시성 확보
        drawRoundRect(
            color = primaryColor.copy(alpha = 0.5f),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = 5f)
        )
        // 코너 부분 네온 효과 (두꺼운 반투명 선)
        drawPath(
            path = path,
            color = primaryColor.copy(alpha = 0.6f),
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
private val NeonOrange = Color(0xFFFF6D00) // 생동감 넘치는 주황색 네온 (Deep Neon Orange)
