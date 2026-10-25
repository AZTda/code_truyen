package com.truyencloud.compose.screens.home

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.data.SavedWord

/**
 * Hàng mỏng "Từ hôm nay" dưới "Đang đọc": từ tiếng Anh (serif 16 SemiBold) + nghĩa ngắn (13sp phụ) + nút phát âm. Chạm vào
 * hàng mở Từ vựng. Không có từ đã lưu thì không hiện gì.
 */
@Composable
fun WordOfTheDayCard(
    word: SavedWord?,
    onSpeak: (String) -> Unit = {},
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (word == null) return
    val colors = LocalCustomColors.current
    val meaning = word.contextMeaningVi.ifBlank { word.englishDefinition }
    // Chữ hệ thống lớn: từ chiếm gần hết dòng nên cho nghĩa xuống thêm một dòng thay vì cắt còn vài chữ
    val meaningLines = if (LocalDensity.current.fontScale > 1.15f) 2 else 1

    FlatCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(text = "Từ hôm nay", style = AppText.caption, color = colors.textTertiary, maxLines = 1)
                Row {
                    Text(
                        text = word.word,
                        style = AppText.bookTitle.copy(fontSize = 16.sp, lineHeight = 22.sp),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp).alignByBaseline()
                    )
                    if (meaning.isNotBlank()) {
                        Text(
                            text = meaning,
                            style = AppText.secondary,
                            color = colors.textSecondary,
                            maxLines = meaningLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).alignByBaseline().padding(start = 8.dp)
                        )
                    }
                }
            }
            QuietIconButton(
                icon = TruyenCloudIcons.VolumeUp,
                contentDescription = "Phát âm từ ${word.word}",
                onClick = { onSpeak(word.word) }
            )
        }
    }
}
