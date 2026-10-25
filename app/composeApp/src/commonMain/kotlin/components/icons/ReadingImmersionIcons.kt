package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Đọc sâu: gợi ý, độ sáng, cuộn tự động, tốc độ. */
interface ReadingImmersionIcons {
    val Lightbulb: ImageVector get() = ImmersionHolder.lightbulb
    val Sun: ImageVector get() = ImmersionHolder.sun
    val AutoScroll: ImageVector get() = ImmersionHolder.autoScroll
    val Bolt: ImageVector get() = ImmersionHolder.bolt
}

private object ImmersionHolder {
    val lightbulb by lazyIcon(
        "Lightbulb",
        "M9 18h6 M10 21.5h4",
        "M12 2.5a6.5 6.5 0 0 0-3.8 11.8c.6.5.8 1.2.8 1.9V16h6v-.8c0-.7.2-1.4.8-1.9A6.5 6.5 0 0 0 12 2.5Z"
    )
    val sun by lazyIcon(
        "Sun",
        "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z",
        "M12 2.5v2 M12 19.5v2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M2.5 12h2 M19.5 12h2 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4"
    )
    val autoScroll by lazyIcon("AutoScroll", "M6 4h12", "M7 9l5 5 5-5", "M7 14.5l5 5 5-5")
    val bolt by lazyIcon("Bolt", "M13 2.5L5 13.5h6l-1 8 8-11h-6Z")
}
