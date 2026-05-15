package app.trashai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trashai.ui.BottomCardSheet
import app.trashai.ui.ItemRuleBody
import app.trashai.clarify.ClarificationChips
import app.trashai.vision.CameraScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    TrashAiApp()
                }
            }
        }
    }
}

@Composable
private fun TrashAiApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = remember { AppState(context.applicationContext) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.preloadDb() }

    Box(Modifier.fillMaxSize()) {
        CameraScreen(
            detections = state.lastDetections,
            onDetections = { dets -> viewModel.onDetections(dets) },
            onCaptureBytes = { bytes -> scope.launch { viewModel.onCapture(bytes) } },
            onTapDetection = { d -> viewModel.pickDetection(d) },
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 32.dp, start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x66000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    "현재 위치: 고양시 일산동구",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            when (val s = state.sheetState) {
                SheetState.Idle -> IdleHint(
                    detectionCount = state.lastDetections.size,
                    onAskAi = { viewModel.startAskUser() },
                )

                is SheetState.Loading -> BottomCardSheet(title = s.message) {
                    Text("…", color = Color.Gray)
                }

                is SheetState.Item -> BottomCardSheet(
                    title = s.rule.itemName,
                    subtitle = s.rule.primaryCategory?.let { "분류 · $it" },
                    onDismiss = { viewModel.dismissSheet() },
                ) {
                    ItemRuleBody(s.rule, regionLabel = "고양시 일산동구 기준")
                    if (s.alternates.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        ClarificationChips(
                            candidates = s.alternates,
                            onPick = { viewModel.pickItem(it.itemId) },
                            headline = "다른 후보가 있나요?",
                            hint = null,
                        )
                    }
                }

                is SheetState.Clarify -> BottomCardSheet(
                    title = "정확한 품목을 골라주세요",
                    subtitle = "인식이 확실하지 않습니다",
                    onDismiss = { viewModel.dismissSheet() },
                ) {
                    ClarificationChips(
                        candidates = s.candidates,
                        onPick = { viewModel.pickItem(it.itemId) },
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { viewModel.startAskUser(reason = "어떤 후보도 맞지 않나요?") },
                    ) { Text("AI에게 직접 설명하기") }
                }

                is SheetState.Confirming -> BottomCardSheet(
                    title = "이게 맞나요?",
                    subtitle = "(${s.sourceLabel}) ${s.rule.itemName}",
                    onDismiss = { viewModel.dismissSheet() },
                ) {
                    s.rule.appSummary?.let { Text(it, color = Color(0xFF333333)) }
                    s.rule.dischargeMethod?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Color(0xFF555555), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.confirmYes() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D5A27)),
                        ) { Text("네, 맞아요") }
                        OutlinedButton(onClick = { viewModel.confirmNo() }) {
                            Text("아니에요")
                        }
                    }
                }

                is SheetState.AskUser -> AskUserSheet(
                    state = s,
                    onSubmit = { viewModel.submitUserText(it) },
                    onDismiss = { viewModel.dismissSheet() },
                )

                is SheetState.Empty -> BottomCardSheet(
                    title = "매칭되는 품목이 없습니다",
                    subtitle = s.detail,
                    onDismiss = { viewModel.dismissSheet() },
                ) {
                    Text(
                        "촬영 버튼으로 정밀 분석을 시도하거나, AI에게 직접 설명해보세요.",
                        color = Color(0xFF333333),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.startAskUser() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D5A27)),
                    ) { Text("AI에게 직접 설명하기") }
                }

                is SheetState.Error -> BottomCardSheet(
                    title = "오류",
                    onDismiss = { viewModel.dismissSheet() },
                ) {
                    Text(s.message, color = Color.Red)
                }
            }
        }
    }
}

@Composable
private fun IdleHint(detectionCount: Int, onAskAi: () -> Unit) {
    val message = when {
        detectionCount == 0 -> "물체를 화면 안에 비춰주세요"
        detectionCount == 1 -> "인식 중…"
        else -> "원하는 박스를 탭하세요 ($detectionCount 개)"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x88000000))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(message, color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onAskAi,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        ) { Text("AI 묻기") }
    }
}

@Composable
private fun AskUserSheet(
    state: SheetState.AskUser,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    BottomCardSheet(
        title = "AI에게 설명하기",
        subtitle = state.prompt,
        onDismiss = onDismiss,
    ) {
        if (state.history.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (turn in state.history.takeLast(4)) {
                    val tag = if (turn.from == SheetState.Speaker.Ai) "AI" else "나"
                    val color =
                        if (turn.from == SheetState.Speaker.Ai) Color(0xFF2D5A27) else Color(0xFF1B1B1B)
                    Text("$tag: ${turn.text}", color = color, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("예: '뚜껑이 안 빠지는 화장품 통'") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val v = text.trim()
                    if (v.isNotEmpty()) {
                        text = ""
                        onSubmit(v)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D5A27)),
            ) { Text("보내기") }
            OutlinedButton(onClick = onDismiss) { Text("취소") }
        }
    }
}
