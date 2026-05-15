package app.trashai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EnergySavingsLeaf
import androidx.compose.material.icons.outlined.Recycling
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trashai.data.ItemRule

@Composable
fun ItemRuleBody(rule: ItemRule, regionLabel: String? = null) {
    // ---- Title row -----------------------------------------------------------
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                rule.itemName,
                fontSize = Tokens.TitleSize,
                fontWeight = FontWeight.Bold,
                color = Tokens.TextPrimary,
                lineHeight = androidx.compose.ui.unit.TextUnit(26f, androidx.compose.ui.unit.TextUnitType.Sp),
            )
            rule.primaryCategory?.let {
                Spacer(Modifier.height(Tokens.Sp4))
                Text(it, fontSize = Tokens.SubtitleSize, color = Tokens.TextSecondary)
            }
        }
        regionLabel?.let { RegionBadge(it) }
    }

    Spacer(Modifier.height(Tokens.Sp16))

    // ---- "이렇게 배출하세요" --------------------------------------------------
    SectionHeader(icon = Icons.Outlined.EnergySavingsLeaf, text = "이렇게 배출하세요")

    Spacer(Modifier.height(Tokens.Sp8))

    val steps = (rule.dischargeMethod ?: rule.appSummary ?: "분리배출 정보가 없습니다.")
        .split('.', '。', '\n')
        .map { it.trim() }
        .filter { it.length >= 2 }
        .take(5)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(Tokens.Radius12),
                spotColor = Color(0x1A000000),
                ambientColor = Color(0x0A000000)
            )
            .background(Tokens.Surface, RoundedCornerShape(Tokens.Radius12))
            .border(1.dp, Tokens.Divider, RoundedCornerShape(Tokens.Radius12))
            .padding(Tokens.Sp16),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val displaySteps = steps.take(3)
        displaySteps.forEachIndexed { i, step ->
            StepColumn(i + 1, step, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            if (i < displaySteps.size - 1) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .padding(vertical = Tokens.Sp8)
                        .background(Tokens.Divider)
                )
            }
        }
    }

    rule.featureText?.let {
        Spacer(Modifier.height(Tokens.Sp12))
        InfoBlock(icon = Icons.Outlined.EnergySavingsLeaf, title = "특징", body = it, accent = Tokens.PrimaryGreenSoft, iconTint = Tokens.PrimaryGreen)
    }
    rule.cautionText?.let {
        Spacer(Modifier.height(Tokens.Sp8))
        InfoBlock(icon = Icons.Outlined.WarningAmber, title = "주의", body = it, accent = Tokens.Warning, iconTint = Tokens.WarningText)
    }

    // ---- Region rule highlight ----------------------------------------------
    if (regionLabel != null) {
        Spacer(Modifier.height(Tokens.Sp16))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.Radius16))
                .background(Tokens.PrimaryGreenSoft)
                .padding(Tokens.Sp12),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Tokens.PrimaryGreen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(Tokens.Sp12))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$regionLabel 지역 규칙",
                    fontWeight = FontWeight.Bold,
                    color = Tokens.PrimaryGreen,
                    fontSize = Tokens.SectionSize,
                )
                Spacer(Modifier.height(Tokens.Sp4))
                Text(
                    "지정된 분리수거 봉투 또는 용기에 배출하세요. 자세한 일정은 지자체 공지를 확인해주세요.",
                    fontSize = Tokens.CaptionSize,
                    color = Tokens.PrimaryGreen,
                )
            }
        }
    }

    Spacer(Modifier.height(Tokens.Sp12))
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = app.trashai.R.drawable.flogo),
            contentDescription = "출처 로고",
            modifier = Modifier.height(14.dp)
        )
        Spacer(Modifier.width(Tokens.Sp4))
        Text(
            "출처 · ${rule.sourceName}",
            fontSize = Tokens.TagSize,
            color = Tokens.TextSecondary,
        )
    }
}

// ---------------------------------------------------------------------------
// Subcomponents
// ---------------------------------------------------------------------------

@Composable
private fun RegionBadge(label: String) {
    Row(
        modifier = Modifier
            .background(Tokens.PrimaryGreenTint, CircleShape)
            .border(0.5.dp, Tokens.PrimaryGreen.copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.EnergySavingsLeaf,
            contentDescription = null,
            tint = Tokens.PrimaryGreen,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = Tokens.TagSize,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.PrimaryGreen,
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Tokens.PrimaryGreen,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Tokens.Sp6))
        Text(
            text = text,
            fontSize = Tokens.SectionSize,
            fontWeight = FontWeight.Bold,
            color = Tokens.PrimaryGreen,
        )
    }
}

@Composable
private fun StepColumn(number: Int, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Tokens.PrimaryGreenSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                color = Tokens.PrimaryGreen,
                fontWeight = FontWeight.Bold,
                fontSize = Tokens.CaptionSize,
            )
        }
        Spacer(Modifier.height(Tokens.Sp8))
        Text(
            body.replaceFirst(Regex("^\\d+\\.\\s*"), ""),
            color = Tokens.TextPrimary,
            fontSize = 12.sp,
            lineHeight = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun InfoBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    accent: Color,
    iconTint: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent, RoundedCornerShape(Tokens.Radius12))
            .border(1.dp, iconTint.copy(alpha = 0.15f), RoundedCornerShape(Tokens.Radius12))
            .padding(Tokens.Sp16),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Tokens.Sp8))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = Tokens.CaptionSize,
                fontWeight = FontWeight.SemiBold,
                color = iconTint,
            )
            Spacer(Modifier.height(Tokens.Sp4))
            Text(body, fontSize = Tokens.CaptionSize, color = Tokens.TextPrimary)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFFF8F9FA)
@Composable
private fun ItemRuleBodyPreview() {
    app.trashai.data.ItemRule(
        itemId = "test1",
        itemName = "투명 페트병",
        primaryCategory = "플라스틱",
        dischargeMethod = "1. 내용물을 비우고 물로 헹굽니다.\n2. 라벨을 제거합니다.\n3. 찌그러뜨려 뚜껑을 닫습니다.",
        featureText = "무색 투명한 생수, 음료수병만 해당됩니다.",
        cautionText = "유색 플라스틱이나 커피컵은 일반 플라스틱으로 배출하세요.",
        appSummary = "깨끗이 씻어서 배출",
        sourceName = "고양시청",
        sourceUrl = ""
    ).let { rule ->
        Column(modifier = Modifier.padding(Tokens.Sp16)) {
            ItemRuleBody(rule = rule, regionLabel = "고양시 일산동구")
        }
    }
}
