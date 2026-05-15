package app.trashai

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.EnergySavingsLeaf
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Recycling
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.trashai.ui.ItemRuleBody
import app.trashai.ui.Tokens
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

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) viewModel.fetchRegionFromGps()
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- Top half: camera (or captured image when result is up) -------
        Box(modifier = Modifier.weight(1.1f).fillMaxWidth()) {
            CameraScreen(
                onCaptureBytes = { bytes -> scope.launch { viewModel.onCapture(bytes) } },
                capturedJpeg = state.lastCapturedJpeg,
            )

            // Top status bar — pin pill (left) + AI ask (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp, start = Tokens.Sp16, end = Tokens.Sp16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Sp8),
            ) {
                LocationPill(
                    label = state.regionLabel,
                    loading = state.regionLoading,
                    onTap = {
                        locationPermLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                IconChip(
                    icon = Icons.Outlined.Chat,
                    label = "AI 묻기",
                    onClick = { viewModel.startAskUser() },
                )
            }

            // "← 다시 스캔" pill, bottom-left when a captured image is showing
            if (state.lastCapturedJpeg != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(Tokens.Sp16)
                        .clip(RoundedCornerShape(Tokens.Radius24))
                        .background(Tokens.ScrimStrong)
                        .clickable { viewModel.dismissSheet() }
                        .padding(horizontal = Tokens.Sp12, vertical = Tokens.Sp8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(Tokens.Sp4))
                    Text("다시 스캔", color = Color.White, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ---- Bottom half: info card --------------------------------------
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            color = Tokens.Surface,
            shape = RoundedCornerShape(topStart = Tokens.Radius24, topEnd = Tokens.Radius24),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(Tokens.Sp24)) {
                BottomCardContent(
                    state = state,
                    onPickItem = { viewModel.pickItem(it) },
                    onConfirmYes = { viewModel.confirmYes() },
                    onConfirmNo = { viewModel.confirmNo() },
                    onAskAi = { viewModel.startAskUser() },
                    onSubmitText = { viewModel.submitUserText(it) },
                    onDismiss = { viewModel.dismissSheet() },
                )
            }
        }
    }
}

@Composable
private fun LocationPill(
    label: String,
    loading: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.Radius24))
            .background(Tokens.Scrim)
            .clickable(onClick = onTap)
            .padding(horizontal = Tokens.Sp12, vertical = Tokens.Sp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.LocationOn,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Tokens.Sp6))
        Text(
            text = if (loading) "위치 가져오는 중…" else label,
            color = Color.White,
            fontSize = Tokens.CaptionSize,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
        )
        Spacer(Modifier.width(Tokens.Sp8))
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "위치 갱신",
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun IconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Radius24))
            .background(Tokens.Scrim)
            .clickable(onClick = onClick)
            .padding(horizontal = Tokens.Sp12, vertical = Tokens.Sp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Tokens.Sp6))
        Text(label, color = Color.White, fontSize = Tokens.TagSize, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BottomCardContent(
    state: AppUiState,
    onPickItem: (String) -> Unit,
    onConfirmYes: () -> Unit,
    onConfirmNo: () -> Unit,
    onAskAi: () -> Unit,
    onSubmitText: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        when (val s = state.sheetState) {
            SheetState.Idle -> IdleCardContent()

            is SheetState.Loading -> CenteredCaption(s.message)

            is SheetState.Item -> {
                ItemRuleBody(s.rule, regionLabel = state.regionLabel)
                if (s.alternates.isNotEmpty()) {
                    Spacer(Modifier.height(Tokens.Sp16))
                    ClarificationChips(
                        candidates = s.alternates,
                        onPick = { onPickItem(it.itemId) },
                        headline = "다른 후보",
                        hint = null,
                    )
                }
            }

            is SheetState.Clarify -> {
                ClarificationChips(
                    candidates = s.candidates,
                    onPick = { onPickItem(it.itemId) },
                )
                Spacer(Modifier.height(Tokens.Sp16))
                OutlinedButton(
                    onClick = onAskAi,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Tokens.Radius12),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Tokens.PrimaryGreen),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Tokens.PrimaryGreen)
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Tokens.Sp8))
                    Text("AI에게 직접 설명하기", fontWeight = FontWeight.SemiBold)
                }
            }

            is SheetState.Confirming -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(Tokens.PrimaryGreenSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.HelpOutline, contentDescription = null, tint = Tokens.PrimaryGreen, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(Tokens.Sp12))
                    Column {
                        Text("이게 맞나요?", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Tokens.TextPrimary)
                        Spacer(Modifier.height(Tokens.Sp4))
                        Text("${s.sourceLabel} · ${s.rule.itemName}", fontSize = Tokens.CaptionSize, color = Tokens.TextSecondary)
                    }
                }
                Spacer(Modifier.height(Tokens.Sp16))

                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.Radius12)).background(Tokens.SurfaceMuted).padding(Tokens.Sp16)
                ) {
                    s.rule.appSummary?.let { Text(it, color = Tokens.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    s.rule.dischargeMethod?.let {
                        Spacer(Modifier.height(Tokens.Sp6))
                        Text(it, color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize, lineHeight = 16.sp)
                    }
                }
                Spacer(Modifier.height(Tokens.Sp16))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onConfirmNo) { Text("아니에요", color = Tokens.TextSecondary, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.width(Tokens.Sp8))
                    Button(
                        onClick = onConfirmYes,
                        colors = ButtonDefaults.buttonColors(containerColor = Tokens.PrimaryGreen),
                        shape = RoundedCornerShape(Tokens.Radius12),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Tokens.Sp8))
                        Text("네, 맞아요", fontWeight = FontWeight.Bold)
                    }
                }
            }

            is SheetState.AskUser -> AskUserContent(s, onSubmitText, onDismiss)

            is SheetState.Empty -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFFFF3E0)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.SearchOff, contentDescription = null, tint = Color(0xFFB26A00), modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(Tokens.Sp12))
                    Column {
                        Text("매칭되는 품목이 없어요", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Tokens.TextPrimary)
                        Spacer(Modifier.height(Tokens.Sp4))
                        Text(s.detail, fontSize = Tokens.CaptionSize, color = Tokens.TextSecondary)
                    }
                }
                Spacer(Modifier.height(Tokens.Sp16))
                Text(
                    "다른 영역을 박스로 그려서 다시 분석하거나, AI에게 직접 설명해주세요.",
                    color = Tokens.TextPrimary,
                    fontSize = Tokens.BodySize,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(Tokens.Sp16))
                Button(
                    onClick = onAskAi,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Tokens.PrimaryGreen),
                    shape = RoundedCornerShape(Tokens.Radius12),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Tokens.Sp8))
                    Text("AI에게 직접 설명하기", fontWeight = FontWeight.Bold)
                }
            }

            is SheetState.Error -> {
                val isInfo = s.message.startsWith("✅")
                val tintColor = if (isInfo) Tokens.PrimaryGreen else Tokens.Danger
                val bgColor = if (isInfo) Tokens.PrimaryGreenSoft else Color(0xFFFFEBEE)
                val icon = if (isInfo) Icons.Outlined.Info else Icons.Outlined.ErrorOutline

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(bgColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = tintColor, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(Tokens.Sp12))
                    Text(if (isInfo) "정보" else "오류", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tintColor)
                }
                Spacer(Modifier.height(Tokens.Sp16))
                Text(s.message, color = Tokens.TextPrimary, fontSize = Tokens.BodySize)
            }
        }
    }
}

@Composable
private fun CardHeader(title: String, subtitle: String?, accent: Color = Tokens.TextPrimary) {
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent)
    subtitle?.let {
        Spacer(Modifier.height(Tokens.Sp4))
        Text(it, fontSize = Tokens.CaptionSize, color = Tokens.TextSecondary)
    }
}

@Composable
private fun CenteredCaption(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Tokens.TextSecondary, fontSize = Tokens.BodySize)
    }
}

@Composable
private fun IdleCardContent() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Tokens.PrimaryGreenTint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Recycling,
                    contentDescription = null,
                    tint = Tokens.PrimaryGreen,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(Tokens.Sp12))
            Column {
                Text(
                    "사물을 인식하세요",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Tokens.TextPrimary,
                )
                Spacer(Modifier.height(Tokens.Sp4))
                Text(
                    "녹색 박스를 탭하거나, 화면을 드래그해 영역을 선택",
                    fontSize = Tokens.CaptionSize,
                    color = Tokens.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(Tokens.Sp16))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.EnergySavingsLeaf,
                contentDescription = null,
                tint = Tokens.PrimaryGreen,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(Tokens.Sp4))
            Text(
                "지자체 공식 데이터 기반의 분리수거 가이드",
                fontSize = Tokens.CaptionSize,
                color = Tokens.TextSecondary,
            )
        }
    }
}

@Composable
private fun AskUserContent(
    s: SheetState.AskUser,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Tokens.PrimaryGreenSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = Tokens.PrimaryGreen,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(Tokens.Sp12))
            Column {
                Text("AI에게 설명하기", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Tokens.TextPrimary)
                Spacer(Modifier.height(Tokens.Sp4))
                Text(s.prompt, fontSize = Tokens.CaptionSize, color = Tokens.TextSecondary)
            }
        }
        Spacer(Modifier.height(Tokens.Sp16))

        if (s.history.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Tokens.Radius12))
                    .background(Tokens.SurfaceMuted)
                    .padding(Tokens.Sp12),
                verticalArrangement = Arrangement.spacedBy(Tokens.Sp8)
            ) {
                for (turn in s.history.takeLast(4)) {
                    val isAi = turn.from == SheetState.Speaker.Ai
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = if (isAi) "AI" else "ME",
                            color = if (isAi) Tokens.PrimaryGreen else Tokens.TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(24.dp).padding(top = 2.dp)
                        )
                        Text(
                            text = turn.text,
                            color = Tokens.TextPrimary,
                            fontSize = Tokens.CaptionSize,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Tokens.Sp12))
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("예: '뚜껑이 안 빠지는 화장품 통'", color = Tokens.TextSecondary, fontSize = Tokens.BodySize) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            shape = RoundedCornerShape(Tokens.Radius16),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Tokens.Surface,
                unfocusedContainerColor = Tokens.SurfaceMuted,
                focusedBorderColor = Tokens.PrimaryGreen,
                unfocusedBorderColor = Color.Transparent,
            )
        )
        Spacer(Modifier.height(Tokens.Sp16))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) {
                Text("취소", color = Tokens.TextSecondary, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(Tokens.Sp8))
            Button(
                onClick = {
                    val v = text.trim()
                    if (v.isNotEmpty()) {
                        text = ""
                        onSubmit(v)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Tokens.PrimaryGreen),
                shape = RoundedCornerShape(Tokens.Radius12),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Tokens.Sp8))
                Text("보내기", fontWeight = FontWeight.Bold)
            }
        }
    }
}

