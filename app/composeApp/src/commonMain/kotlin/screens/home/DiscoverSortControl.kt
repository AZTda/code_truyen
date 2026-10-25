package com.truyencloud.compose.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.shared.library.DiscoverSort

/**
 * Ô chọn bảng của Khám phá (SegmentedControl). Ba ô trên màn 360dp: "Dành cho bạn" / "Mới cập nhật" chỉ vừa một dòng khi chữ
 * hệ thống gần cỡ chuẩn, chữ lớn hơn thì dùng nhãn ngắn. Khách chỉ có hai ô nên luôn đủ chỗ cho nhãn đầy đủ.
 */
@Composable
internal fun DiscoverSortControl(
    sorts: List<DiscoverSort>,
    selected: DiscoverSort,
    onSelect: (DiscoverSort) -> Unit,
    modifier: Modifier = Modifier
) {
    val compact = sorts.size > 2 && LocalDensity.current.fontScale > 1.15f
    SegmentedControl(
        items = sorts,
        selected = selected,
        onSelect = onSelect,
        label = { DiscoverPresentation.segmentLabel(it, compact) },
        modifier = modifier
    )
}
