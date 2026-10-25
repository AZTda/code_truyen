package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Điều hướng và 5 tab chính. Tab chọn dùng bản `*Filled`; Discover là la bàn (không còn ngôi sao lấp lánh). */
interface NavigationIcons {
    val Bookshelf: ImageVector get() = NavIcons.bookshelf
    val BookshelfFilled: ImageVector get() = NavIcons.bookshelfFilled
    val Discover: ImageVector get() = NavIcons.discover
    val DiscoverFilled: ImageVector get() = NavIcons.discoverFilled
    val Hub: ImageVector get() = NavIcons.hub
    val Settings: ImageVector get() = NavIcons.settings
    val SettingsFilled: ImageVector get() = NavIcons.settingsFilled
    val School: ImageVector get() = NavIcons.school
    val SchoolFilled: ImageVector get() = NavIcons.schoolFilled
    val ExpandMore: ImageVector get() = NavIcons.expandMore
    val ExpandLess: ImageVector get() = NavIcons.expandLess
    val Close: ImageVector get() = NavIcons.close
    val Clear: ImageVector get() = NavIcons.clear
    val Search: ImageVector get() = NavIcons.search
}

private const val GEAR = "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73" +
    "l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38" +
    "a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18" +
    "a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08" +
    "a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08" +
    "a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2Z"
private const val GEAR_HUB = "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z"
private const val CAP_TOP = "M21.4 10.9a1 1 0 0 0 0-1.8L12.8 5.2a2 2 0 0 0-1.7 0L2.6 9.1a1 1 0 0 0 0 1.8l8.5 3.9a2 2 0 0 0 1.7 0Z"

private object NavIcons {
    val bookshelf by lazyIcon(
        "Bookshelf",
        "M3 5.6C5.4 4.5 8.9 4.6 12 6.8C15.1 4.6 18.6 4.5 21 5.6V18.4C18.6 17.3 15.1 17.4 12 19.6C8.9 17.4 5.4 17.3 3 18.4Z",
        "M12 6.8V19.6"
    )
    val bookshelfFilled by lazyIcon(
        "BookshelfFilled",
        "f:M2.4 5.3C5 4.1 8.7 4.2 11.3 6.2V19.3C8.7 17.5 5 17.4 2.4 18.7Z",
        "f:M21.6 5.3C19 4.1 15.3 4.2 12.7 6.2V19.3C15.3 17.5 19 17.4 21.6 18.7Z"
    )
    val discover by lazyIcon("Discover", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z", "M15.9 8.1l-2 5.8-5.8 2 2-5.8Z")
    val discoverFilled by lazyIcon("DiscoverFilled", "e:M12 21.9a9.9 9.9 0 1 0 0-19.8 9.9 9.9 0 0 0 0 19.8Z M15.9 8.1l-2 5.8-5.8 2 2-5.8Z")
    val hub by lazyIcon(
        "Hub",
        "M12 14.4a2.4 2.4 0 1 0 0-4.8 2.4 2.4 0 0 0 0 4.8Z",
        "M5 6.9a1.9 1.9 0 1 0 0-3.8 1.9 1.9 0 0 0 0 3.8Z",
        "M19 6.9a1.9 1.9 0 1 0 0-3.8 1.9 1.9 0 0 0 0 3.8Z",
        "M12 21.9a1.9 1.9 0 1 0 0-3.8 1.9 1.9 0 0 0 0 3.8Z",
        "M10.3 10.3L6.4 6.4 M13.7 10.3l3.9-3.9 M12 14.4v3.7"
    )
    val settings by lazyIcon("Settings", GEAR, GEAR_HUB)
    val settingsFilled by lazyIcon("SettingsFilled", "e:$GEAR $GEAR_HUB")
    val school by lazyIcon("School", CAP_TOP, "M22 10v6", "M6 12.5V16a6 3 0 0 0 12 0v-3.5")
    val schoolFilled by lazyIcon("SchoolFilled", "b:$CAP_TOP", "M22 10v6", "M6 12.5V16a6 3 0 0 0 12 0v-3.5")
    val expandMore by lazyIcon("ExpandMore", "M6 9l6 6 6-6")
    val expandLess by lazyIcon("ExpandLess", "M6 15l6-6 6 6")
    val close by lazyIcon("Close", "M6 6l12 12 M18 6L6 18")
    val clear by lazyIcon("Clear", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z", "M9 9l6 6 M15 9l-6 6")
    val search by lazyIcon("Search", "M10.5 18a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15Z", "M21 21l-5.2-5.2")
}
