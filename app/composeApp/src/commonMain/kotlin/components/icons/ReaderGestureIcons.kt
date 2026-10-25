package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Chế độ lật trang của trình đọc. */
interface ReaderGestureIcons {
    val PageModeCurl: ImageVector get() = GestureHolder.pageModeCurl
    val PageModeScroll: ImageVector get() = GestureHolder.pageModeScroll
}

private object GestureHolder {
    val pageModeCurl by lazyIcon(
        "PageModeCurl",
        "M6 3.5h12A1.5 1.5 0 0 1 19.5 5v10l-5 5.5H6A1.5 1.5 0 0 1 4.5 19V5A1.5 1.5 0 0 1 6 3.5Z",
        "M19.5 15h-4a1 1 0 0 0-1 1v4.5"
    )
    val pageModeScroll by lazyIcon(
        "PageModeScroll",
        "M7 3.5h10A1.5 1.5 0 0 1 18.5 5v14a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19V5A1.5 1.5 0 0 1 7 3.5Z",
        "M12 8v8 M9.5 10.5L12 8l2.5 2.5 M9.5 13.5L12 16l2.5-2.5"
    )
}
