package com.truyencloud.compose.screens.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.icons.TruyenCloudIcons

/**
 * Mục lục trống. Hai trường hợp khác nhau: truyện thật sự chưa có chương nào, hoặc bộ lọc / ô tìm không khớp chương nào
 * (bản cũ hiện lẫn lộn lời "chưa phát hành chương" cho cả hai).
 */
@Composable
fun ChapterListEmpty(noChaptersAtAll: Boolean, modifier: Modifier = Modifier) {
    if (noChaptersAtAll) {
        EmptyState(
            message = "Tác phẩm chưa phát hành chương",
            subMessage = "Tác giả mới chỉ mở phần giới thiệu cho độc giả xem trước. " +
                "Chạm biểu tượng làm mới ở góc trên để quét khi có chương đầu tiên.",
            icon = TruyenCloudIcons.MenuBook,
            modifier = modifier
        )
    } else {
        EmptyState(
            message = "Không có chương phù hợp",
            subMessage = "Thử số chương hoặc từ khóa khác, hoặc tắt bộ lọc chương đã tải.",
            icon = TruyenCloudIcons.Search,
            modifier = modifier
        )
    }
}
