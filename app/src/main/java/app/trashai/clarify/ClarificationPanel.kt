package app.trashai.clarify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import app.trashai.data.KeywordHit

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
    headline: String = "어떤 품목인가요?",
    hint: String? = "가장 가까운 항목을 선택하면 정확한 가이드를 보여드립니다.",
) {
    if (candidates.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(headline, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        hint?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, fontSize = 12.sp, color = Color.Gray)
        }
        Spacer(Modifier.height(10.dp))
        ChipFlow(candidates, onPick)
    }
}

@Composable
private fun ChipFlow(items: List<KeywordHit>, onPick: (KeywordHit) -> Unit) {
    // Simple wrap using Column-of-Rows; avoids extra accompanist-flow dep.
    val rows = items.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (h in row) {
                    AssistChip(
                        onClick = { onPick(h) },
                        label = { Text(h.itemName) },
                        shape = RoundedCornerShape(20.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color(0x142D5A27),
                            labelColor = Color(0xFF2D5A27),
                        ),
                    )
                }
            }
        }
    }
}
