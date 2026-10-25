package com.truyencloud.compose.screens.home

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.SearchField
import com.truyencloud.compose.theme.Spacing

/**
 * Hàng đầu của lớp tìm kiếm toàn màn hình: ô tìm kiếm kiểu iOS (kính lúp, nút xóa) và chữ "Hủy" để đóng. Phím tìm của bàn
 * phím gọi [onSubmit]. Mở ra là gõ được ngay: ô tự lấy tiêu điểm nên bàn phím hiện lên.
 */
@Composable
fun HomeSearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    SearchField(
        value = query,
        onValueChange = onQueryChange,
        // Ngắn để không bị cắt ở chữ hệ thống lớn: ô chỉ rộng khoảng 220dp sau kính lúp và nút xóa
        placeholder = "Tên truyện, tác giả, link",
        onSearch = onSubmit,
        onCancel = onClose,
        requestFocusOnShow = true,
        // Nút "Hủy" đã có đệm ngang 12dp nên lề phải chỉ cần 8dp để chữ thẳng với lề trang 20dp
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = Spacing.pageH, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.xs)
    )
}
