package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.WordExplainResponse

/** Phần phân tích của mục từ: định nghĩa tiếng Anh, gốc từ, cụm từ kết hợp và câu ví dụ. Mỗi khối chỉ hiện khi máy chủ có dữ liệu thật. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnglishWordAnalysisSection(
    data: WordExplainResponse,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        if (data.englishDefinition.isNotBlank()) {
            FlatCard(modifier = Modifier.fillMaxWidth(), outlined = true) {
                Column {
                    ReaderSectionLabel("Định nghĩa (tiếng Anh)")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "“${data.englishDefinition}”",
                        style = AppText.body.copy(fontStyle = FontStyle.Italic),
                        color = colors.textPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.md))
        }

        if (data.etymology.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InputShape)
                    .background(colors.accentSoft)
                    .padding(14.dp)
            ) {
                Text(text = "Gốc từ", style = AppText.secondaryStrong, color = colors.onAccentSoft)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = data.etymology, style = AppText.body, color = colors.textPrimary)
            }
            Spacer(modifier = Modifier.height(Spacing.md))
        }

        if (data.collocations.isNotEmpty()) {
            ReaderSectionLabel("Cụm từ kết hợp")
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                data.collocations.take(3).forEach { phrase ->
                    ReaderTag(text = phrase, fill = colors.card, content = colors.textPrimary)
                }
            }
            Spacer(modifier = Modifier.height(Spacing.md))
        }

        if (data.exampleSentences.isNotEmpty()) {
            ReaderSectionLabel("Ví dụ")
            Spacer(modifier = Modifier.height(4.dp))
            data.exampleSentences.take(2).forEach { ex ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Text(text = "•", style = AppText.body, color = colors.accent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = ex,
                        style = AppText.body.copy(fontStyle = FontStyle.Italic),
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.lg))
        }
    }
}
