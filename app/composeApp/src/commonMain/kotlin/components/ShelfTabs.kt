package com.truyencloud.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.truyencloud.shared.library.LibraryShelves

/**
 * Thanh chọn kệ (Đang đọc / Hoàn thành / Bộ sưu tập) dạng [SegmentedControl]. Nhãn lấy từ [LibraryShelves] (cùng nguồn với
 * tờ hành động và trang chi tiết).
 *
 * @param counts số truyện từng kệ — hiện cạnh nhãn để thấy truyện vừa chuyển kệ đã sang đâu (ẩn khi người dùng phóng chữ
 *   hệ thống quá 1,1 lần vì ô không đủ rộng).
 */
@Composable
fun ShelfTabs(
    selectedShelf: String,
    onSelectShelf: (String) -> Unit,
    modifier: Modifier = Modifier,
    counts: Map<String, Int> = emptyMap()
) {
    val shelves = LibraryShelves.all
    // Ba ô trên màn 360dp: nhãn kèm số ("Hoàn thành 12") chỉ vừa khi chữ gần cỡ chuẩn; chữ lớn hơn thì bỏ số, chỉ giữ nhãn
    val showCounts = LocalDensity.current.fontScale <= 1.1f
    SegmentedControl(
        options = shelves.map { shelf ->
            val count = counts[shelf.id] ?: 0
            if (showCounts && count > 0) "${shelf.label} $count" else shelf.label
        },
        selectedIndex = shelves.indexOfFirst { it.id == selectedShelf }.coerceAtLeast(0),
        onSelect = { onSelectShelf(shelves[it].id) },
        modifier = modifier
    )
}
