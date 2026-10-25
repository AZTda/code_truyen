package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Tải xuống, xuất, giờ, cảnh báo, dịch ngầm ban đêm, sách điện tử EPUB. */
interface DownloadStorageIcons {
    val Download: ImageVector get() = DownloadHolder.download
    val Schedule: ImageVector get() = DownloadHolder.schedule
    val Warning: ImageVector get() = DownloadHolder.warning
    val Export: ImageVector get() = DownloadHolder.export
    val MoonStar: ImageVector get() = DownloadHolder.moonStar
    val BookDown: ImageVector get() = DownloadHolder.bookDown
    val Zap: ImageVector get() = DownloadHolder.zap
}

private object DownloadHolder {
    val download by lazyIcon("Download", TRAY_DOWN)
    val schedule by lazyIcon("Schedule", CIRCLE_24, "M12 7v5l3.5 2")
    val warning by lazyIcon(
        "Warning",
        "M10.3 4.2L2.8 17.4A2 2 0 0 0 4.5 20.4h15a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0Z", "M12 9.5v4", dot(12f, 17f, 1.1f)
    )
    val export by lazyIcon("Export", TRAY_UP)
    val moonStar by lazyIcon(
        "MoonStar",
        "M18 5h4",
        "M20 3v4",
        "M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401"
    )
    val bookDown by lazyIcon(
        "BookDown",
        "M12 13V7",
        "M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H19a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H6.5a1 1 0 0 1 0-5H20",
        "m9 10 3 3 3-3"
    )
    val zap by lazyIcon(
        "Zap",
        "M15.914 4a1.5 1.5 0 0 0-2.474-1.561l-9 9A1.5 1.5 0 0 0 5.5 14h4.002a.5.5 0 0 1 .471.666L8.086 20a1.5 1.5 0 0 0 2.475 1.56l9-9A1.5 1.5 0 0 0 18.5 10h-3.997a.5.5 0 0 1-.472-.667z"
    )
}
