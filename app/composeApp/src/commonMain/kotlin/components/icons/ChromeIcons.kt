package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Biểu tượng cho thanh công cụ, hàng danh sách và trạng thái trống: chevron, ba chấm, lọc, sắp xếp, nạp tệp... */
interface ChromeIcons {
    val ChevronRight: ImageVector get() = ChromeHolder.chevronRight
    val ChevronLeft: ImageVector get() = ChromeHolder.chevronLeft
    val More: ImageVector get() = ChromeHolder.more
    val MoreVertical: ImageVector get() = ChromeHolder.moreVertical
    val Sort: ImageVector get() = ChromeHolder.sort
    val Import: ImageVector get() = ChromeHolder.importTray
    val Heart: ImageVector get() = ChromeHolder.heart
    val HeartFilled: ImageVector get() = ChromeHolder.heartFilled
    val Person: ImageVector get() = ChromeHolder.person
    val Moon: ImageVector get() = ChromeHolder.moon
    val Globe: ImageVector get() = ChromeHolder.globe
    val Logout: ImageVector get() = ChromeHolder.logout
    val List: ImageVector get() = ChromeHolder.list
    val Cloud: ImageVector get() = ChromeHolder.cloud
    val Chat: ImageVector get() = ChromeHolder.chat
    val Wand: ImageVector get() = ChromeHolder.wand
    val Image: ImageVector get() = ChromeHolder.image
    val Bell: ImageVector get() = ChromeHolder.bell
    val Layers: ImageVector get() = ChromeHolder.layers
    val Target: ImageVector get() = ChromeHolder.target
}

internal const val CLOUD_PATH = "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"
private const val HEART = "M12 20.2l-1.1-1C6.4 15.2 3.5 12.6 3.5 9.4A4.4 4.4 0 0 1 7.9 5c1.6 0 3.2.8 4.1 2.1" +
    "C12.9 5.8 14.5 5 16.1 5a4.4 4.4 0 0 1 4.4 4.4c0 3.2-2.9 5.8-7.4 9.8Z"

private object ChromeHolder {
    val chevronRight by lazyIcon("ChevronRight", "M9 5l7 7-7 7")
    val chevronLeft by lazyIcon("ChevronLeft", "M15 5l-7 7 7 7")
    val more by lazyIcon("More", dot(5f, 12f, 1.5f), dot(12f, 12f, 1.5f), dot(19f, 12f, 1.5f))
    val moreVertical by lazyIcon("MoreVertical", dot(12f, 5f, 1.5f), dot(12f, 12f, 1.5f), dot(12f, 19f, 1.5f))
    val sort by lazyIcon("Sort", "M4 7h9 M4 12h6 M4 17h3 M17.5 6v12 M14.5 15l3 3 3-3")
    val importTray by lazyIcon("Import", "M12 4v11 M7.5 10.5L12 15l4.5-4.5 M4 15.5v2A2.5 2.5 0 0 0 6.5 20h11a2.5 2.5 0 0 0 2.5-2.5v-2")
    val heart by lazyIcon("Heart", HEART)
    val heartFilled by lazyIcon("HeartFilled", "b:$HEART")
    val person by lazyIcon("Person", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z", "M4.5 20.5c.6-3.6 3.8-6 7.5-6s6.9 2.4 7.5 6")
    val moon by lazyIcon("Moon", "M20.5 14.2A8.5 8.5 0 1 1 9.8 3.5a6.7 6.7 0 0 0 10.7 10.7Z")
    val globe by lazyIcon(
        "Globe",
        "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z",
        "M3 12h18",
        "M12 3c2.5 2.5 3.8 5.5 3.8 9s-1.3 6.5-3.8 9c-2.5-2.5-3.8-5.5-3.8-9S9.5 5.5 12 3Z"
    )
    val logout by lazyIcon("Logout", "M9 21H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3", "M16 17l5-5-5-5", "M21 12H9")
    val list by lazyIcon("List", "M9 6h12 M9 12h12 M9 18h12", dot(4.5f, 6f), dot(4.5f, 12f), dot(4.5f, 18f))
    val cloud by lazyIcon("Cloud", CLOUD_PATH)
    val chat by lazyIcon("Chat", "M21 15a2 2 0 0 1-2 2H8l-5 4V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z", "M8 9h8 M8 13h5")
    val wand by lazyIcon("Wand", "M4.5 19.5L16 8", "M18 3v4 M16 5h4", "M7 3v3 M5.5 4.5h3")
    val image by lazyIcon(
        "Image",
        "M5.5 4h13A1.5 1.5 0 0 1 20 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18.5v-13A1.5 1.5 0 0 1 5.5 4Z",
        "M4 16l4.5-4.5L13 16l2.5-2.5L20 18",
        dot(9f, 9f, 1.4f)
    )
    val bell by lazyIcon("Bell", "M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15Z", "M10 21h4")
    val layers by lazyIcon("Layers", "M12 3l9 4.5-9 4.5-9-4.5Z", "M3 12l9 4.5 9-4.5", "M3 16.5l9 4.5 9-4.5")
    val target by lazyIcon(
        "Target",
        "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z",
        "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10Z",
        dot(12f, 12f, 1.4f)
    )
}
