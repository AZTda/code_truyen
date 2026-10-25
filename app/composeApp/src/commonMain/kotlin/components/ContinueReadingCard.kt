package com.truyencloud.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayTitle

/**
 * Thẻ "Đang đọc" trong hàng cuộn ngang: bìa 96x136, tên truyện serif 14sp (2 dòng), chú thích "Chương 12 / 29" và thanh
 * tiến độ 3dp. Chạm để đọc tiếp. Dùng trong `LazyRow(horizontalArrangement = spacedBy(12.dp))`, tối đa 6 thẻ.
 */
@Composable
fun ContinueReadingCard(
    novel: Novel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val caption = chapterCaption(novel, compact = true)
    Column(
        modifier = modifier
            .width(Spacing.coverWidth)
            .pressAndHold(onClick = onClick)
            .semantics { contentDescription = "${novel.displayTitle}, ${chapterCaption(novel)}. Đọc tiếp" }
    ) {
        NovelCoverImage(
            novel = novel,
            modifier = Modifier.fillMaxWidth().aspectRatio(COVER_RATIO)
        )
        // Thẻ rộng 96dp: giới hạn phóng chữ để tên truyện 2 dòng và chú thích còn đọc được
        CappedFontScale(max = 1.2f) {
            Column {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = novel.displayTitle,
                    style = AppText.bookTitleCompact,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = caption,
                    style = AppText.captionRegular,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        progressOf(novel)?.let {
            Spacer(Modifier.height(6.dp))
            ProgressBar3(progress = it, minVisible = 0.04f)
        }
    }
}

/**
 * "Chương 12 / 29", "Đang ở chương 12" (chưa biết tổng) hoặc "Chưa đọc". [compact] rút "Chương" thành "Ch." khi số dài
 * (vd. "Ch. 412 / 1432") để vừa một dòng dưới bìa 96dp.
 */
internal fun chapterCaption(novel: Novel, compact: Boolean = false): String {
    val current = novel.lastReadChapter
    val total = novel.totalChapters
    val word = if (compact && "$current$total".length > 5) "Ch." else "Chương"
    return when {
        current > 0 && total > 0 -> "$word $current / $total"
        current > 0 -> if (compact) "Ch. $current" else "Đang ở chương $current"
        total > 0 -> "$total chương"
        else -> "Chưa đọc"
    }
}
