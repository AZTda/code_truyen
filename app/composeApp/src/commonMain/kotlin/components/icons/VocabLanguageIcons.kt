package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Từ vựng, bộ truyện và các lĩnh vực nghĩa. */
interface VocabLanguageIcons {
    val NovelCollection: ImageVector get() = VocabHolder.novelCollection
    val DomainTime: ImageVector get() = VocabHolder.domainTime
    val DomainFate: ImageVector get() = VocabHolder.domainFate
    val DomainAcademic: ImageVector get() = VocabHolder.domainAcademic
    val DomainWorkplace: ImageVector get() = VocabHolder.domainWorkplace
    val DomainEmotion: ImageVector get() = VocabHolder.domainEmotion
    val DomainAesthetics: ImageVector get() = VocabHolder.domainAesthetics
    val Flashcards: ImageVector get() = VocabHolder.flashcards
}

private object VocabHolder {
    val novelCollection by lazyIcon(
        "NovelCollection",
        "M6.5 3.5h11A1.5 1.5 0 0 1 19 5v14a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19V5a1.5 1.5 0 0 1 1.5-1.5Z",
        "M9 3.5v17", "M12.5 8h3.5"
    )
    val domainTime by lazyIcon(
        "DomainTime",
        "M7 3.5h10 M7 20.5h10",
        "M8 3.5v3a4 4 0 0 0 1.6 3.2L12 12l2.4-2.3A4 4 0 0 0 16 6.5v-3",
        "M8 20.5v-3a4 4 0 0 1 1.6-3.2L12 12l2.4 2.3a4 4 0 0 1 1.6 3.2v3"
    )
    val domainFate by lazyIcon("DomainFate", CIRCLE_24, "M12 3a4.5 4.5 0 0 1 0 9 4.5 4.5 0 0 0 0 9", dot(12f, 7.5f, 1f))
    val domainAcademic by lazyIcon(
        "DomainAcademic",
        "M21.4 10.9a1 1 0 0 0 0-1.8L12.8 5.2a2 2 0 0 0-1.7 0L2.6 9.1a1 1 0 0 0 0 1.8l8.5 3.9a2 2 0 0 0 1.7 0Z",
        "M22 10v6", "M6 12.5V16a6 3 0 0 0 12 0v-3.5"
    )
    val domainWorkplace by lazyIcon(
        "DomainWorkplace",
        "M4 8h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z",
        "M9 8V6a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2", "M3 13h18"
    )
    val domainEmotion by lazyIcon(
        "DomainEmotion",
        "M12 20.2l-1.1-1C6.4 15.2 3.5 12.6 3.5 9.4A4.4 4.4 0 0 1 7.9 5c1.6 0 3.2.8 4.1 2.1C12.9 5.8 14.5 5 16.1 5" +
            "a4.4 4.4 0 0 1 4.4 4.4c0 3.2-2.9 5.8-7.4 9.8Z"
    )
    val domainAesthetics by lazyIcon(
        "DomainAesthetics",
        "M12 3a9 9 0 0 0 0 18c1.1 0 1.8-.8 1.8-1.8 0-.5-.2-.9-.5-1.3-.3-.4-.5-.8-.5-1.3 0-1 .8-1.8 1.8-1.8H17a4 4 0 0 0 4-4C21 6.5 17 3 12 3Z",
        dot(7.5f, 11f, 1.1f), dot(10f, 7.5f, 1.1f), dot(14.5f, 7.5f, 1.1f)
    )
    val flashcards by lazyIcon(
        "Flashcards",
        "M4 7.5A1.5 1.5 0 0 1 5.5 6h10A1.5 1.5 0 0 1 17 7.5v11A1.5 1.5 0 0 1 15.5 20h-10A1.5 1.5 0 0 1 4 18.5Z",
        "M7.5 4h11A1.5 1.5 0 0 1 20 5.5V16",
        "M7.5 11.5h6", "M7.5 15h4"
    )
}
