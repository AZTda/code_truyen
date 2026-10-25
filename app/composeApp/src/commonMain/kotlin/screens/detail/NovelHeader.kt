package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.COVER_RATIO
import com.truyencloud.compose.components.NovelCoverImage
import com.truyencloud.compose.components.NovelSources
import com.truyencloud.compose.components.ProgressBar3
import com.truyencloud.compose.components.chapterCaption
import com.truyencloud.compose.components.progressOf
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayAuthor
import com.truyencloud.shared.data.displayTitle

private val HeaderCoverWidth = 112.dp

/** Tên truyện ở đầu trang: serif SemiBold 22/28 (tên sách là chỗ duy nhất ngoài trình đọc dùng serif). */
private val HeaderTitleStyle = AppText.screenTitle.copy(fontFamily = ReaderFont, fontSize = 22.sp, lineHeight = 28.sp)

/**
 * Đầu trang chi tiết: bìa 112dp bên trái; bên phải là tên truyện, tác giả, một dòng "N chương · M đã tải · nguồn" và
 * (nếu đã đọc) thanh tiến độ mảnh kèm "Chương 12 / 29". Không còn huy hiệu trang trí.
 *
 * @param downloadedCount số chương đã có bản dịch trên máy (đọc offline được); 0 thì không hiện.
 */
@Composable
fun NovelHeader(
    novel: Novel,
    modifier: Modifier = Modifier,
    downloadedCount: Int = 0,
    serverTranslatedCount: Int = 0,
    isOvernightEnabled: Boolean = false
) {
    val colors = LocalCustomColors.current
    // Khoảng trắng không ngắt dòng trong từng cụm để khi hẹp chỉ xuống dòng giữa các cụm ("Tấn Giang" không bị tách đôi)
    val meta = listOfNotNull(
        novel.totalChapters.takeIf { it > 0 }?.let { "$it chương" },
        serverTranslatedCount.takeIf { it > 0 }?.let { "$it đã dịch AI" },
        downloadedCount.takeIf { it > 0 }?.let { "$it trong máy" },
        if (isOvernightEnabled) "Đang dịch ngầm" else null,
        NovelSources.label(novel.sourceId)
    ).joinToString(" · ") { it.replace(' ', '\u00A0') }
    val progress = progressOf(novel)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        NovelCoverImage(
            novel = novel,
            modifier = Modifier.width(HeaderCoverWidth).aspectRatio(COVER_RATIO),
            artScale = HeaderCoverWidth.value / 96f
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = novel.displayTitle,
                style = HeaderTitleStyle,
                color = colors.textPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )

            val author = novel.displayAuthor
            if (author.isNotBlank()) {
                Text(
                    text = author,
                    style = AppText.secondary,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = AppText.captionRegular,
                    color = colors.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (progress != null) {
                Spacer(modifier = Modifier.height(12.dp))
                ProgressBar3(progress = progress, minVisible = 0.04f)
                Text(
                    text = chapterCaption(novel),
                    style = AppText.captionRegular,
                    color = colors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
