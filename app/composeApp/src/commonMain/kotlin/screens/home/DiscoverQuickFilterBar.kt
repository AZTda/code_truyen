package com.truyencloud.compose.screens.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.SearchField
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayAuthor
import com.truyencloud.shared.data.displayTitle
import com.truyencloud.shared.library.DiscoverCategoryClassifier
import com.truyencloud.shared.library.LibraryListLogic

internal enum class DiscoverLocalSort(val label: String) {
    DEFAULT("Mặc định"),
    MOST_TRANSLATED("Nhiều chương dịch"),
    MOST_CHAPTERS("Dài chương nhất");

    fun next(): DiscoverLocalSort = when (this) {
        DEFAULT -> MOST_TRANSLATED
        MOST_TRANSLATED -> MOST_CHAPTERS
        MOST_CHAPTERS -> DEFAULT
    }
}

internal class DiscoverQuickFilterState {
    var query by mutableStateOf("")
    var onlyOvernight by mutableStateOf(false)
    var onlyTranslated by mutableStateOf(false)
    var hideInLibrary by mutableStateOf(false)
    var localSort by mutableStateOf(DiscoverLocalSort.DEFAULT)

    fun reset() {
        query = ""
        onlyOvernight = false
        onlyTranslated = false
        hideInLibrary = false
        localSort = DiscoverLocalSort.DEFAULT
    }

    val isActive: Boolean
        get() = query.isNotBlank() || onlyOvernight || onlyTranslated || hideInLibrary || localSort != DiscoverLocalSort.DEFAULT
}

internal fun applyDiscoverQuickFilters(
    base: List<Novel>,
    category: String,
    state: DiscoverUiState,
    libraryKeys: Set<String>,
    quickFilter: DiscoverQuickFilterState
): List<Novel> {
    val feed = state.current
    val byCat = when (category) {
        DiscoverCategoryClassifier.ALL -> base
        DiscoverCategoryClassifier.OVERNIGHT -> base.filter { state.isOvernight(it) }
        DiscoverCategoryClassifier.AI_TRANSLATED -> base.filter { state.translatedOf(it) > 0 }
        else -> base.filter { feed.categoryOf(it) == category }
    }
    val q = quickFilter.query.trim().lowercase()
    val filtered = byCat.filter { novel ->
        if (quickFilter.onlyOvernight && !state.isOvernight(novel)) return@filter false
        if (quickFilter.onlyTranslated && state.translatedOf(novel) <= 0) return@filter false
        if (quickFilter.hideInLibrary && LibraryListLogic.isInLibrary(novel, libraryKeys)) return@filter false
        if (q.isNotEmpty()) {
            val hay = "${novel.title} ${novel.titleVi} ${novel.displayTitle} ${novel.author} ${novel.displayAuthor} ${feed.reasonOf(novel)}".lowercase()
            if (q !in hay) return@filter false
        }
        true
    }
    return when (quickFilter.localSort) {
        DiscoverLocalSort.DEFAULT -> filtered
        DiscoverLocalSort.MOST_TRANSLATED -> filtered.sortedWith(
            compareByDescending<Novel> { state.translatedOf(it) }.thenByDescending { it.totalChapters }
        )
        DiscoverLocalSort.MOST_CHAPTERS -> filtered.sortedByDescending { it.totalChapters }
    }
}

@Composable
internal fun DiscoverQuickFilterBar(
    quickFilter: DiscoverQuickFilterState,
    totalCount: Int,
    overnightCount: Int,
    translatedCount: Int,
    notInLibraryCount: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SearchField(
            value = quickFilter.query,
            onValueChange = { quickFilter.query = it },
            placeholder = "Lọc nhanh trong $totalCount truyện (tên, tác giả)…",
            modifier = Modifier.padding(horizontal = Spacing.pageH)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageH),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip8(
                label = "Dịch ngầm",
                trailingText = if (overnightCount > 0) "$overnightCount" else null,
                selected = quickFilter.onlyOvernight,
                leadingIcon = TruyenCloudIcons.MoonStar,
                onClick = { quickFilter.onlyOvernight = !quickFilter.onlyOvernight }
            )
            FilterChip8(
                label = "Đã dịch AI",
                trailingText = if (translatedCount > 0) "$translatedCount" else null,
                selected = quickFilter.onlyTranslated,
                leadingIcon = TruyenCloudIcons.Translate,
                onClick = { quickFilter.onlyTranslated = !quickFilter.onlyTranslated }
            )
            FilterChip8(
                label = "Chưa trong tủ",
                trailingText = if (notInLibraryCount > 0) "$notInLibraryCount" else null,
                selected = quickFilter.hideInLibrary,
                leadingIcon = TruyenCloudIcons.Bookshelf,
                onClick = { quickFilter.hideInLibrary = !quickFilter.hideInLibrary }
            )
            FilterChip8(
                label = "Xếp: ${quickFilter.localSort.label}",
                selected = quickFilter.localSort != DiscoverLocalSort.DEFAULT,
                leadingIcon = TruyenCloudIcons.Sort,
                onClick = { quickFilter.localSort = quickFilter.localSort.next() }
            )
        }
    }
}
