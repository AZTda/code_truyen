package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Đọc truyện: sách, đánh dấu trang, dịch, đồng bộ, dấu tích. */
interface ReaderIcons {
    val MenuBook: ImageVector get() = ReaderHolder.menuBook
    val BookOpen: ImageVector get() = ReaderHolder.bookOpen
    val BookmarkBorder: ImageVector get() = ReaderHolder.bookmarkBorder
    val BookmarkFilled: ImageVector get() = ReaderHolder.bookmarkFilled
    val Translate: ImageVector get() = ReaderHolder.translate
    val Refresh: ImageVector get() = ReaderHolder.refresh
    val Sync: ImageVector get() = ReaderHolder.sync
    val Check: ImageVector get() = ReaderHolder.check
    val CheckCircle: ImageVector get() = ReaderHolder.checkCircle
    val SwapVert: ImageVector get() = ReaderHolder.swapVert
    val FormatSize: ImageVector get() = ReaderHolder.formatSize
    val GlossaryBook: ImageVector get() = ReaderHolder.glossaryBook
    val HistoryTimeline: ImageVector get() = ReaderHolder.historyTimeline
}

private object ReaderHolder {
    val menuBook by lazyIcon("MenuBook", "M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H19a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H6.5a1 1 0 0 1 0-5H20")
    val bookOpen by lazyIcon("BookOpen", OPEN_BOOK, OPEN_BOOK_SPINE)
    val bookmarkBorder by lazyIcon("BookmarkBorder", BOOKMARK)
    val bookmarkFilled by lazyIcon("BookmarkFilled", "b:$BOOKMARK")
    val translate by lazyIcon("Translate", "m5 8 6 6", "m4 14 6-6 2-3", "M2 5h12", "M7 2h1", "m22 22-5-10-5 10", "M14 18h6")
    val refresh by lazyIcon("Refresh", "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5")
    val sync by lazyIcon(
        "Sync",
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5"
    )
    val check by lazyIcon("Check", "M5 12.5l4.5 4.5L19 7.5")
    val checkCircle by lazyIcon("CheckCircle", CIRCLE_24, "M8 12.3l2.8 2.8L16 9.8")
    val swapVert by lazyIcon("SwapVert", "M8 20V4 M4 8l4-4 4 4 M16 4v16 M12 16l4 4 4-4")
    val formatSize by lazyIcon(
        "FormatSize",
        "m2 16 4.04-9.69a.5.5 0 0 1 .92 0L11 16", "M22 9v7", "M3.3 13h6.4", "M18 16a4 4 0 1 1 0-8 4 4 0 0 1 0 8Z"
    )
    val glossaryBook by lazyIcon(
        "GlossaryBook",
        "M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H19a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H6.5a1 1 0 0 1 0-5H20",
        "M9 7h6", "M9 11h4", "M14 2v6l-2-1.5L10 8V2"
    )
    val historyTimeline by lazyIcon(
        "HistoryTimeline",
        "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
        "M3 3v5h5",
        "M12 7v5l3.5 2"
    )
}
