package app.trashai.clarify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import app.trashai.data.KeywordHit
import app.trashai.ui.Tokens

/**
 * Shown when matching is uncertain (multiple candidates with similar weights,
 * or only fuzzy hits). User taps a chip to commit to one item, then we render
 * its full card via the same CardComposer path as the certain flow
 * (architecture §4.3).
 */
@Composable
fun ClarificationChips(
    candidates: List<KeywordHit>,
    onPick: (KeywordHit) -> Unit,
    modifier: Modifier = Modifier,
    headline: String = "혹시 이 품목인가요?",
    hint: String? = "원하시는 항목을 선택해 주세요.",
) {
    if (candidates.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(Tokens.Radius12),
                spotColor = Color(0x1A000000),
                ambientColor = Color(0x0A000000)
            )
            .background(Tokens.Surface, RoundedCornerShape(Tokens.Radius12))
            .padding(Tokens.Sp16)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = Tokens.PrimaryGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Tokens.Sp8))
            Column {
                Text(headline, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Tokens.TextPrimary)
                hint?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, fontSize = 12.sp, color = Tokens.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        ChipFlow(candidates, onPick)
    }
}

@Composable
private fun ChipFlow(items: List<KeywordHit>, onPick: (KeywordHit) -> Unit) {
    // Simple wrap using Column-of-Rows; avoids extra accompanist-flow dep.
    val rows = items.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (h in row) {
                    AssistChip(
                        onClick = { onPick(h) },
                        label = { Text(h.itemName, fontWeight = FontWeight.SemiBold) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Tokens.PrimaryGreen.copy(alpha = 0.3f)),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Tokens.PrimaryGreenSoft,
                            labelColor = Tokens.PrimaryGreen,
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
