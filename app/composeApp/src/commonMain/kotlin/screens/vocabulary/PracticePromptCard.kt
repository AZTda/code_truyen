package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.shared.practice.PracticeItem
import com.truyencloud.shared.practice.PracticeMode

/**
 * Đề của một lượt luyện: nhãn việc cần làm 13sp ở trên, nội dung lớn bên dưới (serif cho câu trong truyện và từ cần luyện).
 */
@Composable
fun PracticePromptCard(
    item: PracticeItem,
    onReplayAudio: () -> Unit
) {
    val c = LocalCustomColors.current
    val wordStyle = AppText.wordTitle.copy(fontSize = 28.sp, lineHeight = 36.sp)

    FlatCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (item.mode) {
                PracticeMode.CLOZE -> {
                    SectionLabel("Điền từ vào câu trong truyện")
                    Text(
                        text = item.promptText,
                        style = AppText.body.copy(fontFamily = ReaderFont, fontSize = 18.sp, lineHeight = 28.sp),
                        color = c.textPrimary
                    )
                    if (item.word.bookTitle.isNotBlank()) {
                        Text(
                            text = item.word.bookTitle +
                                if (item.word.chapterNumber > 0) ", chương ${item.word.chapterNumber}" else "",
                            style = AppText.captionRegular,
                            color = c.textTertiary
                        )
                    }
                }

                PracticeMode.TYPING, PracticeMode.SMART -> {
                    SectionLabel("Gõ lại từ tiếng Anh")
                    Text(
                        text = item.promptText,
                        style = AppText.bodyStrong.copy(fontSize = 20.sp, lineHeight = 28.sp),
                        color = c.textPrimary
                    )
                    // Gợi ý chữ cái đầu: đơn cách để đếm được số ô trống
                    Text(
                        text = item.hintVi,
                        style = AppText.body.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
                        color = c.textSecondary
                    )
                }

                PracticeMode.LISTENING -> {
                    SectionLabel("Nghe và chép lại")
                    TonalButton(
                        text = "Nghe lại câu",
                        onClick = onReplayAudio,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = TruyenCloudIcons.VolumeUp
                    )
                }

                PracticeMode.COLLOCATION -> {
                    SectionLabel("Chọn cụm từ đúng chuẩn bản ngữ")
                    Text(text = item.promptText, style = wordStyle, color = c.textPrimary)
                    if (item.hintVi.isNotBlank()) {
                        Text(text = item.hintVi, style = AppText.secondary, color = c.textSecondary)
                    }
                }

                PracticeMode.RECOGNITION -> {
                    // Đề chỉ hiện từ tiếng Anh; đáp án (nghĩa tiếng Việt) nằm ở các lựa chọn bên dưới.
                    SectionLabel("Chọn nghĩa tiếng Việt đúng")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = item.promptText, style = wordStyle, color = c.textPrimary, modifier = Modifier.weight(1f))
                        QuietIconButton(TruyenCloudIcons.VolumeUp, "Nghe từ", onReplayAudio)
                    }
                }

                PracticeMode.PRONUNCIATION -> {
                    SectionLabel("Luyện phát âm")
                    Text(text = item.promptText, style = wordStyle, color = c.textPrimary)
                    val ipa = item.word.ipaUs.ifBlank { item.word.ipaUk.ifBlank { "/${item.word.word}/" } }
                    Text(text = ipa, style = AppText.body, color = c.textSecondary)
                    if (item.hintVi.isNotBlank()) {
                        Text(text = item.hintVi, style = AppText.secondary, color = c.textSecondary)
                    }
                    TonalButton(
                        text = "Nghe mẫu",
                        onClick = onReplayAudio,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = TruyenCloudIcons.VolumeUp
                    )
                }
            }
        }
    }
}
