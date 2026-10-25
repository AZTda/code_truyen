package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.PillShape
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayAuthor
import com.truyencloud.shared.data.displayTitle

data class FeaturedItem(
    val novel: Novel,
    /** Nhãn ngắn trên đầu thẻ (vd. "Đọc nhiều"), viết hoa chữ đầu câu. */
    val tag: String,
    val subtitle: String,
    /** Truyện đã có trong tủ: nút đổi thành "Đã có trong tủ" kèm dấu tích (chạm vào vẫn mở trang chi tiết). */
    val inLibrary: Boolean = false
)

/**
 * Hàng nổi bật đầu Khám phá: các thẻ phẳng màu thẻ (bìa + tên + tác giả + lý do 12sp) trượt ngang theo trang, dấu chấm
 * trang bên dưới. Không gradient, không viền màu.
 */
@Composable
fun TrendingCarousel(
    items: List<FeaturedItem>,
    onSelectNovel: (Novel) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.pageH)
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { items.size })
    val colors = LocalCustomColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        // Thẻ cao cố định: giới hạn phóng chữ để tiêu đề 2 dòng + nút không bị cắt
        CappedFontScale(max = 1.2f) {
            HorizontalPager(
                state = pagerState,
                contentPadding = contentPadding,
                pageSpacing = Spacing.md,
                modifier = Modifier.fillMaxWidth().height(176.dp)
            ) { page ->
                val item = items[page]
                FlatCard(
                    modifier = Modifier.fillMaxSize(),
                    shape = CardShape,
                    contentPadding = PaddingValues(16.dp),
                    onClick = { onSelectNovel(item.novel) }
                ) {
                    FeaturedCardBody(item = item)
                }
            }
        }

        if (items.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(items.size) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (pagerState.currentPage == index) 14.dp else 5.dp, height = 5.dp)
                            .clip(PillShape)
                            .background(if (pagerState.currentPage == index) colors.accent else colors.hairline)
                    )
                }
            }
        }
    }
}

@Composable
private fun FeaturedCardBody(item: FeaturedItem) {
    val colors = LocalCustomColors.current
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = item.tag,
                style = AppText.caption,
                color = colors.onAccentSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Column {
                Text(
                    text = item.novel.displayTitle,
                    style = AppText.bookTitle.copy(fontSize = 17.sp, lineHeight = 22.sp),
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.novel.displayAuthor,
                    style = AppText.author,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.subtitle.isNotBlank()) {
                    Text(
                        text = item.subtitle,
                        style = AppText.captionRegular,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.inLibrary) {
                    Icon(TruyenCloudIcons.Check, contentDescription = null, tint = colors.success, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = if (item.inLibrary) "Đã có trong tủ" else "Đọc tác phẩm",
                    style = AppText.secondaryStrong,
                    color = if (item.inLibrary) colors.textSecondary else colors.accent,
                    maxLines = 1
                )
            }
        }

        NovelCoverImage(
            novel = item.novel,
            modifier = Modifier.fillMaxHeight().aspectRatio(COVER_RATIO)
        )
    }
}
