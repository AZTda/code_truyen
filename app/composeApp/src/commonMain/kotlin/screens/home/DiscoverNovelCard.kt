package com.truyencloud.compose.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.NovelCard
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.data.Novel

/**
 * Thẻ truyện của Khám phá: [NovelCard] kèm lý do máy chủ gợi ý truyện này ([reason], rỗng = không hiện) làm chú thích 12sp
 * màu chữ phụ dưới tên tác giả. Không dùng `footnote` của NovelCard vì nó chỉ có một dòng: câu lý do dài hơn bìa 96dp nên bị
 * cắt giữa câu (ở đây tối đa 3 dòng). Chạm vào chú thích cũng mở truyện như chạm vào bìa.
 */
@Composable
internal fun DiscoverNovelCard(
    novel: Novel,
    reason: String,
    inLibrary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overnightEnabled: Boolean = false,
    translatedChapters: Int = 0
) {
    val colors = LocalCustomColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        NovelCard(
            novel = novel,
            inLibrary = inLibrary,
            overnightEnabled = overnightEnabled,
            translatedChapters = translatedChapters,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        )
        if (reason.isNotBlank()) {
            // Cùng giới hạn phóng chữ với NovelCard để cột ba thẻ không lệch nhau
            CappedFontScale(max = 1.2f) {
                Text(
                    text = reason,
                    style = AppText.captionRegular,
                    color = colors.textSecondary,
                    maxLines = REASON_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        // Không gợn sóng: cùng kiểu chạm lún nhẹ của bìa, chỉ khác là dòng chữ này không co lại
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                )
            }
        }
    }
}

private const val REASON_MAX_LINES = 3
