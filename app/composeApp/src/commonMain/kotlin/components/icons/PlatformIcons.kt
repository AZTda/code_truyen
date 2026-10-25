package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Nền tảng, thông tin và các ký hiệu trợ giúp. Không có ngôi sao lấp lánh: Sparkles là sao 5 cánh. */
interface PlatformIcons {
    val Smartphone: ImageVector get() = PlatformHolder.smartphone
    val Apple: ImageVector get() = PlatformHolder.apple
    val Android: ImageVector get() = PlatformHolder.android
    val Info: ImageVector get() = PlatformHolder.info
    val Sparkles: ImageVector get() = PlatformHolder.sparkles
    val Bookmark: ImageVector get() = PlatformHolder.bookmark
}

private object PlatformHolder {
    val smartphone by lazyIcon(
        "Smartphone",
        "M8 2.5h8A1.5 1.5 0 0 1 17.5 4v16a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 20V4A1.5 1.5 0 0 1 8 2.5Z", "M11 18.5h2"
    )
    val apple by lazyIcon(
        "Apple",
        "f:M15.24 12.97C15.22 10.45 17.29 9.21 17.38 9.15C16.21 7.44 14.39 7.21 13.76 7.18C12.22 7.02 10.74 8.09 9.96 8.09" +
            "C9.18 8.09 7.97 7.19 6.69 7.22C5.02 7.24 3.48 8.19 2.62 9.7C0.89 12.7 2.18 17.14 3.86 19.57C4.68 20.76 5.66 22.09 6.95 22.04" +
            "C8.19 21.99 8.66 21.24 10.16 21.24C11.66 21.24 12.09 22.04 13.38 22.01C14.71 21.99 15.55 20.8 16.37 19.61" +
            "C17.32 18.23 17.71 16.89 17.74 16.82C17.67 16.79 15.26 15.86 15.24 12.97Z " +
            "M12.91 5.3C13.58 4.49 14.03 3.37 13.91 2.24C12.95 2.28 11.79 2.88 11.1 3.69C10.49 4.4 9.95 5.54 10.1 6.64C11.17 6.72 12.24 6.11 12.91 5.3Z"
    )
    val android by lazyIcon(
        "Android",
        "e:M5.5 17.5a6.5 6.5 0 0 1 13 0Z M9.1 14.3a.8.8 0 1 0 1.6 0 .8.8 0 1 0-1.6 0Z M13.3 14.3a.8.8 0 1 0 1.6 0 .8.8 0 1 0-1.6 0Z",
        "M8.2 7.4L7 5.4 M15.8 7.4L17 5.4"
    )
    val info by lazyIcon("Info", CIRCLE_24, "M12 11v5.5", dot(12f, 7.8f, 1.1f))
    val sparkles by lazyIcon(
        "Sparkles",
        "M11.5 2.3a.53.53 0 0 1 .95 0l2.31 4.68a2.12 2.12 0 0 0 1.6 1.16l5.17.75a.53.53 0 0 1 .29.9l-3.74 3.64" +
            "a2.12 2.12 0 0 0-.61 1.88l.88 5.14a.53.53 0 0 1-.77.56l-4.62-2.43a2.12 2.12 0 0 0-1.97 0L6.4 21.01" +
            "a.53.53 0 0 1-.77-.56l.88-5.14a2.12 2.12 0 0 0-.61-1.88L2.16 9.8a.53.53 0 0 1 .29-.91l5.17-.75" +
            "a2.12 2.12 0 0 0 1.6-1.16Z"
    )
    val bookmark by lazyIcon("Bookmark", "b:$BOOKMARK")
}
