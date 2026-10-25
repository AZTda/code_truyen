package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont

/** Thẻ "Nghĩa trong ngữ cảnh câu": nghĩa tiếng Việt, kèm câu nguyên văn trong truyện (serif, nghiêng) nếu có. */
@Composable
fun EnglishWordDefinitionCard(
    contextMeaningVi: String,
    contextSentence: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current

    FlatCard(modifier = modifier.fillMaxWidth(), outlined = true) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ReaderSectionLabel("Nghĩa trong ngữ cảnh câu")
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = contextMeaningVi,
                style = AppText.bodyStrong.copy(fontWeight = FontWeight.Medium),
                color = colors.textPrimary
            )

            if (contextSentence.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InputShape)
                        .background(colors.cardHigh)
                        .padding(12.dp)
                ) {
                    Text(text = "Câu trong truyện", style = AppText.caption, color = colors.textSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "“$contextSentence”",
                        style = AppText.body.copy(fontFamily = ReaderFont, fontStyle = FontStyle.Italic),
                        color = colors.textPrimary
                    )
                }
            }
        }
    }
}
