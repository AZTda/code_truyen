package com.truyencloud.compose.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.ContinueReadingCard
import com.truyencloud.compose.components.SectionHeader
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.Novel

/**
 * Mục "Đang đọc" ở đầu Tủ sách: hàng cuộn ngang tối đa 6 thẻ (bìa 96x136, tên truyện, "Chương 12 / 29", thanh tiến độ).
 * Chạm một thẻ là đọc tiếp. Hàng kéo sát hai mép màn hình ([fullBleed]) nhưng thẻ đầu vẫn thẳng hàng với tiêu đề.
 */
@Composable
internal fun ContinueReadingSection(
    novels: List<Novel>,
    onOpen: (Novel) -> Unit,
    modifier: Modifier = Modifier,
    onOpenHistory: (() -> Unit)? = null
) {
    // Thẻ rộng 96dp chỉ đủ cho "Chương 12 / 29" ở chữ chuẩn; chữ hệ thống lớn hơn thì nới thẻ để chú thích không bị cắt
    val cardWidth = if (LocalDensity.current.fontScale > 1.05f) WIDE_CARD_WIDTH else Spacing.coverWidth
    // Đệm đáy 8dp + khoảng cách 16dp của lưới = nhịp 24dp giữa hai khối lớn
    Column(modifier = modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
        SectionHeader(
            title = "Đang đọc",
            actionLabel = if (onOpenHistory != null) "Nhật ký đọc" else null,
            onAction = onOpenHistory
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        LazyRow(
            modifier = Modifier.fullBleed(),
            contentPadding = PaddingValues(horizontal = Spacing.pageH),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            items(novels, key = { it.id }) { novel ->
                ContinueReadingCard(novel = novel, onClick = { onOpen(novel) }, modifier = Modifier.width(cardWidth))
            }
        }
    }
}

private val WIDE_CARD_WIDTH = 112.dp
