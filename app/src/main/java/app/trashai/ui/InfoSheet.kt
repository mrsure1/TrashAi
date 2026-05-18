package app.trashai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun InfoSheetContent(
    initialTab: String = "개인정보 처리방침",
    onDismiss: () -> Unit = {},
    onTabChange: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(initialTab) }

    LaunchedEffect(selectedTab) {
        onTabChange()
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Header & Close Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = Tokens.Primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(Tokens.Sp12))
            Text(
                "정보 및 법적 고지",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Tokens.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "닫기",
                    tint = Tokens.TextSecondary
                )
            }
        }
        
        Spacer(Modifier.height(Tokens.Sp20))

        // Tab Bar (Segmented Buttons)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.Radius16))
                .background(Tokens.SurfaceMuted)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val tabs = listOf("개인정보 처리방침", "이용 약관")
            tabs.forEach { tab ->
                val isSelected = selectedTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Tokens.Radius12))
                        .background(if (isSelected) Tokens.Primary else Color.Transparent)
                        .clickable { selectedTab = tab }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (isSelected) Color.White else Tokens.TextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(Tokens.Sp24))

        // Content based on selected tab
        when (selectedTab) {
            "개인정보 처리방침" -> {
                LegalSection(
                    icon = Icons.Outlined.Shield,
                    title = "개인정보 및 데이터 처리",
                    content = """
                        • 카메라 권한: 사물 식별을 위한 실시간 영상 분석에만 사용됩니다.
                        • 이미지 데이터: 분석을 위해 Google Gemini API로 전송되며, 서비스 개선 및 분석 외의 용도로는 사용되지 않습니다.
                        • 위치 정보: 거주 지역별 맞춤형 가이드 제공을 위한 참고 자료로만 활용됩니다.
                    """.trimIndent()
                )
            }
            "이용 약관" -> {
                LegalSection(
                    icon = Icons.Outlined.Article,
                    title = "이용 약관",
                    content = """
                        • 본 서비스는 무료로 제공되며, 사용자는 정당한 재활용 문화 정착을 위해 서비스를 이용해야 합니다.
                        • 서비스의 안정적인 운영을 위해 시스템에 과도한 부하를 주는 행위는 제한될 수 있습니다.
                    """.trimIndent()
                )
            }
        }

        Spacer(Modifier.height(Tokens.Sp16))
        HorizontalDivider(color = Tokens.Divider)
        Spacer(Modifier.height(Tokens.Sp20))

        // Copyright & App Info
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "RecycleAI v1.0.0",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.TextSecondary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "© 2026 RecycleAI. All rights reserved.",
                fontSize = 12.sp,
                color = Tokens.TextSecondary.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(Tokens.Sp12))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Tokens.SurfaceMuted),
                shape = RoundedCornerShape(Tokens.Radius12),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("돌아가기", color = Tokens.TextPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(Tokens.Sp24))
        }
    }
}

@Composable
private fun LegalSection(
    icon: ImageVector,
    title: String,
    content: String
) {
    Column(modifier = Modifier.padding(bottom = Tokens.Sp24)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Tokens.Primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Tokens.Sp8))
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.TextPrimary
            )
        }
        Spacer(Modifier.height(Tokens.Sp8))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.Radius12))
                .background(Tokens.SurfaceMuted)
                .padding(Tokens.Sp12)
        ) {
            Text(
                content,
                fontSize = 13.sp,
                color = Tokens.TextSecondary,
                lineHeight = 20.sp
            )
        }
    }
}
