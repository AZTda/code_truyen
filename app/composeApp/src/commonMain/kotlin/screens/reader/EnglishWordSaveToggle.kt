package com.truyencloud.compose.screens.reader

import androidx.compose.runtime.Composable
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.icons.TruyenCloudIcons

/**
 * Nút "Lưu" GHIM Ở ĐẦU bảng tra từ — luôn có, kể cả lúc đang tra hoặc tra lỗi, vì lưu không phụ thuộc kết quả AI.
 * Chạm để lưu ngay; chạm lần nữa để bỏ lưu. Chip phẳng: đã lưu thì tô nhạt màu nhấn kèm biểu tượng dấu trang đặc.
 */
@Composable
internal fun EnglishWordSaveToggle(isSaved: Boolean, savedForm: String? = null, onToggle: () -> Unit) {
    FilterChip8(
        label = when {
            !isSaved -> "Lưu"
            savedForm != null -> "Đã lưu ($savedForm)"
            else -> "Đã lưu"
        },
        selected = isSaved,
        onClick = onToggle,
        leadingIcon = if (isSaved) TruyenCloudIcons.BookmarkFilled else TruyenCloudIcons.BookmarkBorder
    )
}
