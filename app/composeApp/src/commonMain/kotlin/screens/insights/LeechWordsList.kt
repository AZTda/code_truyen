package com.truyencloud.compose.screens.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.data.SavedWord

/**
 * Các từ hay quên nhất (quên từ 8 lần trở lên): từ + nghĩa bên trái, số lần quên và mức CEFR bên phải. Số lần quên viết
 * thành chữ ("Quên 12 lần") nên không cần huy hiệu màu.
 */
@Composable
fun LeechWordsList(words: List<SavedWord>, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    val shown = words.take(10)
    FlatCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    ) {
        CappedFontScale(max = 1.2f) {
            Column(modifier = Modifier.fillMaxWidth()) {
                shown.forEachIndexed { index, word ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(vertical = 8.dp)
                            .semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = word.word,
                                style = AppText.bodyStrong,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (word.contextMeaningVi.isNotBlank()) {
                                Text(
                                    text = word.contextMeaningVi,
                                    style = AppText.secondary,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 64.dp)) {
                            Text(
                                text = "Quên ${word.lapses} lần",
                                style = AppText.caption,
                                color = colors.warning,
                                textAlign = TextAlign.End,
                                maxLines = 1
                            )
                            if (word.cefrLevel.isNotBlank()) {
                                Text(
                                    text = word.cefrLevel,
                                    style = AppText.captionRegular,
                                    color = colors.textTertiary,
                                    textAlign = TextAlign.End,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    if (index < shown.lastIndex) HairlineDivider()
                }
            }
        }
    }
}
