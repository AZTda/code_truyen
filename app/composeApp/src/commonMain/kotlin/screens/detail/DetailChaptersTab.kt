package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.ChapterFlags

/** Trạng thái bộ lọc mục lục — giữ ở cấp màn hình để đổi tab Giới thiệu ↔ Mục lục không mất bộ lọc. */
class ChapterFilterState {
    var query by mutableStateOf("")
    var ascending by mutableStateOf(true)
    var onlyDownloaded by mutableStateOf(false)
}

/** Lọc + sắp xếp mục lục theo [ChapterFilterState]. Tách khỏi giao diện để khỏi tính lại ở mỗi lần vẽ lại. */
internal fun filterChapters(
    chapters: List<Chapter>,
    flags: Map<Int, ChapterFlags>,
    query: String,
    ascending: Boolean,
    onlyDownloaded: Boolean,
    serverTranslated: Set<Int> = emptySet()
): List<Chapter> {
    var list = chapters
    if (onlyDownloaded) {
        list = list.filter { flags[it.chapterNumber]?.isOfflineReady == true || it.chapterNumber in serverTranslated }
    }
    if (query.isNotBlank()) {
        list = list.filter {
            it.chapterNumber.toString().contains(query) ||
                it.titleVi.contains(query, ignoreCase = true) ||
                it.title.contains(query, ignoreCase = true)
        }
    }
    return if (!ascending) list.reversed() else list
}

@Composable
internal fun rememberVisibleChapters(
    chapters: List<Chapter>,
    flags: Map<Int, ChapterFlags>,
    filter: ChapterFilterState,
    serverTranslated: Set<Int> = emptySet()
): List<Chapter> = remember(chapters, flags, filter.query, filter.ascending, filter.onlyDownloaded, serverTranslated) {
    filterChapters(chapters, flags, filter.query, filter.ascending, filter.onlyDownloaded, serverTranslated)
}

/**
 * Nội dung tab Mục lục, thêm thẳng vào LazyColumn của trang (cả trang cuộn như một danh sách duy nhất nên không còn khung
 * cuộn lồng nhau): thanh lọc, rồi các dòng chương hoặc trạng thái trống.
 *
 * @param all toàn bộ mục lục (để phân biệt "chưa có chương" với "bộ lọc không khớp").
 * @param visible mục lục sau khi lọc / sắp xếp, xem [rememberVisibleChapters].
 */
internal fun LazyListScope.detailChaptersTab(
    all: List<Chapter>,
    visible: List<Chapter>,
    flags: Map<Int, ChapterFlags>,
    filter: ChapterFilterState,
    onChapterClick: (Chapter) -> Unit,
    serverTranslated: Set<Int> = emptySet(),
    currentChapter: Int = 0,
    onJumpToCurrentChapter: (() -> Unit)? = null
) {
    // Chưa có chương nào thì thanh lọc vô nghĩa: chỉ hiện trạng thái trống
    if (all.isNotEmpty()) {
        item(key = "chapterFilter") {
            ChapterFilterBar(
                filterQuery = filter.query,
                onFilterChange = { filter.query = it },
                isAscending = filter.ascending,
                onToggleSort = { filter.ascending = !filter.ascending },
                onlyDownloaded = filter.onlyDownloaded,
                onToggleOnlyDownloaded = { filter.onlyDownloaded = !filter.onlyDownloaded },
                currentChapter = currentChapter,
                onJumpToCurrentChapter = onJumpToCurrentChapter,
                modifier = Modifier.padding(horizontal = Spacing.pageH)
            )
        }
    }
    if (visible.isEmpty()) {
        item(key = "chapterEmpty") { ChapterListEmpty(noChaptersAtAll = all.isEmpty()) }
    } else {
        items(visible, key = { it.id }) { chapter ->
            DetailChapterRow(
                chapterNumber = chapter.chapterNumber,
                title = chapter.titleVi.ifBlank { chapter.title },
                isRead = chapter.isRead,
                flags = flags[chapter.chapterNumber],
                isServerTranslated = chapter.chapterNumber in serverTranslated,
                onClick = { onChapterClick(chapter) }
            )
        }
    }
}
