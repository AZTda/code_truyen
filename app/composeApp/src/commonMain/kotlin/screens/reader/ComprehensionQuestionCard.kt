package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.ComprehensionQuestionItem

private val OptionLetters = listOf("A", "B", "C", "D")

/** Trạng thái hiển thị của một đáp án: chọn, đúng, sai hay chưa chọn; luôn kèm biểu tượng / chữ cái nên không chỉ dựa vào màu. */
private class OptionStyle(val fill: Color, val border: Color, val badgeFill: Color, val badgeContent: Color, val icon: ImageVector?)

@Composable
fun ComprehensionQuestionCard(
    index: Int,
    total: Int,
    question: ComprehensionQuestionItem,
    selectedOption: Int?,
    isSubmitted: Boolean,
    onSelectOption: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current

    FlatCard(modifier = modifier.fillMaxWidth(), outlined = true) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Câu $index / $total · ${question.focusType.replaceFirstChar { it.uppercase() }}",
                style = AppText.caption,
                color = colors.textSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = question.question, style = AppText.bodyStrong, color = colors.textPrimary)
            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                question.options.forEachIndexed { optIdx, optText ->
                    val isSelected = selectedOption == optIdx
                    val isCorrect = question.correctIndex == optIdx
                    val style = when {
                        isSubmitted && isCorrect -> OptionStyle(colors.successSoft, colors.success, colors.success, colors.card, TruyenCloudIcons.Check)
                        isSubmitted && isSelected -> OptionStyle(colors.errorSoft, colors.error, colors.error, colors.card, TruyenCloudIcons.Close)
                        isSelected -> OptionStyle(colors.accentSoft, colors.accent, colors.accent, colors.onAccent, null)
                        else -> OptionStyle(Color.Transparent, colors.hairline, colors.cardHigh, colors.textSecondary, null)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(InputShape)
                            .background(style.fill)
                            .border(if (isSelected || (isSubmitted && isCorrect)) 2.dp else 1.dp, style.border, InputShape)
                            .selectable(
                                selected = isSelected,
                                enabled = !isSubmitted,
                                role = Role.RadioButton,
                                onClick = { onSelectOption(optIdx) }
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(24.dp).clip(CircleShape).background(style.badgeFill),
                            contentAlignment = Alignment.Center
                        ) {
                            if (style.icon != null) {
                                Icon(style.icon, contentDescription = null, tint = style.badgeContent, modifier = Modifier.size(16.dp))
                            } else {
                                Text(
                                    text = OptionLetters.getOrElse(optIdx) { "${optIdx + 1}" },
                                    style = AppText.caption,
                                    color = style.badgeContent
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = optText, style = AppText.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    }
                }
            }

            // Lời giải thích hiện sau khi nộp bài
            if (isSubmitted && question.explanation.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InputShape)
                        .background(colors.accentSoft)
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = TruyenCloudIcons.Lightbulb,
                        contentDescription = null,
                        tint = colors.onAccentSoft,
                        modifier = Modifier.padding(top = 2.dp).size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = question.explanation, style = AppText.secondary, color = colors.textPrimary)
                }
            }
        }
    }
}
