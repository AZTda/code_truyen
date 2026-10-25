package com.truyencloud.compose.screens.home

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import com.truyencloud.compose.theme.Spacing

/** Mục chiếm trọn một hàng của lưới: `item(span = FullSpan) { ... }`. */
internal val FullSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/**
 * Cho một mục trong lưới / cột đã chừa lề ngang [inset] kéo dài ra sát hai mép màn hình (hàng cuộn ngang "Đang đọc",
 * băng chuyền Khám phá): mục được đo rộng thêm 2 x [inset] rồi dịch sang trái [inset]. Nội dung bên trong tự chừa lại lề
 * bằng `contentPadding` để thẻ đầu tiên vẫn thẳng hàng với tiêu đề.
 *
 * Lưu ý: vùng nhận chạm vẫn là ô gốc của lưới, nên dải lề 20dp sát mép màn hình không bắt đầu được cú vuốt của hàng này
 * (dải đó cũng là vùng cử chỉ Back của hệ thống); thẻ hiện ra trong dải lề vẫn thấy được nhưng chỉ phần nằm trong ô gốc nhận chạm.
 */
internal fun Modifier.fullBleed(inset: Dp = Spacing.pageH): Modifier = layout { measurable, constraints ->
    val insetPx = inset.roundToPx()
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * insetPx else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-insetPx, 0) }
}

/**
 * Chừa chỗ cho bàn phím nằm phía TRÊN thanh tab dưới. Thanh tab vẫn nằm sau bàn phím, nên chỉ phần bàn phím cao hơn
 * (thanh điều hướng hệ thống + thanh tab) mới che nội dung; dùng cho lớp tìm kiếm để kết quả cuối cuộn tới được.
 */
@Composable
internal fun Modifier.imeAboveTabBar(): Modifier {
    val ime = WindowInsets.ime
    val navigationBars = WindowInsets.navigationBars
    val insets = remember(ime, navigationBars) {
        ime.exclude(navigationBars).exclude(WindowInsets(bottom = Spacing.bottomBarHeight))
    }
    return windowInsetsPadding(insets)
}
