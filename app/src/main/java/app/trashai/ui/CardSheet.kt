package app.trashai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trashai.data.ItemRule

/** Drag handle + rounded top, glass-ish backdrop. */
@Composable
fun BottomCardSheet(
    title: String,
    subtitle: String? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Color(0xF2FFFFFF))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .height(4.dp)
                    .background(Color(0x22000000), RoundedCornerShape(2.dp))
                    .padding(horizontal = 24.dp)
            ) { Spacer(Modifier.height(4.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B1B1B))
                subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, fontSize = 12.sp, color = Color(0xFF2D5A27))
                }
            }
            if (onDismiss != null) {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("닫기", color = Color(0xFF2D5A27))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            content()
        }
    }
}

@Composable
fun ItemRuleBody(rule: ItemRule, regionLabel: String? = null) {
    rule.appSummary?.let { LabelBlock("요약", it) }
    rule.dischargeMethod?.let { LabelBlock("배출 방법", it) }
    rule.featureText?.let { LabelBlock("특징", it) }
    rule.cautionText?.let { LabelBlock("주의", it) }
    Spacer(Modifier.height(16.dp))
    val credit = buildString {
        regionLabel?.let { append(it).append(" · ") }
        append("출처: ").append(rule.sourceName)
    }
    Text(credit, fontSize = 11.sp, color = Color.Gray)
}

@Composable
private fun LabelBlock(title: String, body: String) {
    Spacer(Modifier.height(10.dp))
    Text(title, fontWeight = FontWeight.SemiBold, color = Color(0xFF2D5A27))
    Spacer(Modifier.height(4.dp))
    Text(body, color = Color(0xFF222222))
}
