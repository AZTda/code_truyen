package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.LoreExplainResponse

/**
 * Kết quả tra cứu: thẻ tên Hán Việt + phiên âm + chữ Hán (serif vì là chữ của truyện), rồi nghĩa từ nguyên và ý nghĩa trong bối cảnh.
 */
@Composable
fun LoreEntityDetailCard(
    loreData: LoreExplainResponse,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        FlatCard(modifier = Modifier.fillMaxWidth(), outlined = true) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = loreData.sino_vietnamese.ifBlank { loreData.term },
                        style = AppText.wordTitle.copy(fontSize = 20.sp),
                        color = colors.textPrimary
                    )
                    if (loreData.pinyin.isNotBlank()) {
                        Text(text = "[ ${loreData.pinyin} ]", style = AppText.secondary, color = colors.onAccentSoft)
                    }
                }

                if (loreData.hanzi.isNotBlank()) {
                    Text(
                        text = loreData.hanzi,
                        style = AppText.wordTitle.copy(fontSize = 32.sp, lineHeight = 40.sp),
                        color = colors.textSecondary,
                        fontFamily = ReaderFont
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        ReaderSectionLabel("Nghĩa từ nguyên và phân tích")
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = loreData.definition, style = AppText.body, color = colors.textPrimary)

        if (loreData.lore_context.isNotBlank()) {
            Spacer(modifier = Modifier.height(Spacing.lg))
            ReaderSectionLabel("Ý nghĩa trong bối cảnh tiểu thuyết")
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = loreData.lore_context, style = AppText.body, color = colors.textPrimary)
        }
    }
}
