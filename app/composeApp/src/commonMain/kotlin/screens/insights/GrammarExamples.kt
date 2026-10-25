package com.truyencloud.compose.screens.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.GrammarExample

/** Phần "Ví dụ trong truyện" của một cấu trúc ngữ pháp: tối đa 4 câu trích kèm chương và tên truyện. */
@Composable
internal fun GrammarExamples(examples: List<GrammarExample>, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Ví dụ trong truyện", style = AppText.secondaryStrong, color = colors.textSecondary)

        if (examples.isEmpty()) {
            Text(
                text = "Đang tích lũy các câu văn trích từ những chương bạn đọc...",
                style = AppText.secondary,
                color = colors.textSecondary
            )
            return@Column
        }

        examples.take(4).forEach { example ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.cardHigh)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = "“${example.sentence}”", style = AppText.secondary, color = colors.textPrimary)
                val source = buildList {
                    if (example.chapterNumber > 0) add("Chương ${example.chapterNumber}")
                    if (example.novelTitle.isNotBlank()) add(example.novelTitle)
                }.joinToString(" · ")
                if (source.isNotEmpty()) {
                    Text(text = source, style = AppText.captionRegular, color = colors.textSecondary)
                }
            }
        }
    }
}
