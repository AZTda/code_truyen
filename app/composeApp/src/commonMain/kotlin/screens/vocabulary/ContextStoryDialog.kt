package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.components.IosAlertDialog
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.ContextStoryResponse

/**
 * Vi truyện ngữ cảnh do AI dệt từ các từ đang học. Đang dệt thì hiện hộp chờ không đóng được (như bản cũ: bấm ra ngoài
 * không hủy được yêu cầu đang chạy); có truyện rồi thì mở thành tờ trượt đọc thoải mái.
 */
@Composable
fun ContextStoryDialog(
    isWeaving: Boolean,
    story: ContextStoryResponse?,
    onDismiss: () -> Unit
) {
    if (isWeaving) {
        IosAlertDialog(
            onDismissRequest = {},
            title = "Đang dệt vi truyện",
            message = "AI đang kết nối các từ vựng vào một đoạn truyện ngắn song ngữ.",
            actions = emptyList(),
            dismissOnOutsideTap = false
        ) {
            CircularProgressIndicator(
                color = LocalCustomColors.current.accent,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(28.dp)
            )
        }
    } else if (story != null) {
        StorySheet(story = story, onDismiss = onDismiss)
    }
}

@Composable
private fun StorySheet(story: ContextStoryResponse, onDismiss: () -> Unit) {
    val c = LocalCustomColors.current
    IosSheet(onDismissRequest = onDismiss, title = "Vi truyện ngữ cảnh") {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageH),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            FlatCard(modifier = Modifier.fillMaxWidth(), containerColor = c.cardHigh) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(
                        text = "“${story.englishStory}”",
                        style = AppText.body.copy(fontFamily = ReaderFont),
                        color = c.textPrimary
                    )
                    HairlineDivider()
                    Text(text = story.vietnameseTranslation, style = AppText.secondary, color = c.textSecondary)
                }
            }

            if (story.highlights.isNotEmpty()) {
                SectionLabel("Điểm ngữ cảnh đáng chú ý")
                story.highlights.forEach { hl ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text(text = hl.word, style = AppText.bodyStrong, color = c.textPrimary)
                            if (hl.collocation.isNotBlank() && hl.collocation != hl.word) {
                                StatusLabel(text = hl.collocation)
                            }
                        }
                        if (hl.explanation.isNotBlank()) {
                            Text(text = hl.explanation, style = AppText.secondary, color = c.textSecondary)
                        }
                    }
                }
            }

            PrimaryButton(text = "Đã hiểu", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}
