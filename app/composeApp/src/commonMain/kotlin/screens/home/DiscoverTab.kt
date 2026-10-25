package com.truyencloud.compose.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.CategoryPills
import com.truyencloud.compose.components.LargeTitleScaffold
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.NovelCardSkeleton
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.SectionHeader
import com.truyencloud.compose.components.ShimmerBox
import com.truyencloud.compose.components.TrendingCarousel
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.library.DiscoverCategoryClassifier
import com.truyencloud.shared.library.DiscoverFeeds
import com.truyencloud.shared.library.DiscoverMode
import com.truyencloud.shared.library.DiscoverSort
import com.truyencloud.shared.library.LibraryListLogic

private const val SKELETON_COUNT = 9

/** Chiều cao thẻ nổi bật (= chiều cao thẻ trong TrendingCarousel), để khung xương chiếm đúng chỗ. */
private val FEATURED_HEIGHT = 176.dp

/**
 * Tab Khám phá: tiêu đề lớn, ô chọn bảng ("Dành cho bạn" chỉ khi đã đăng nhập / "Mới cập nhật" / "Đọc nhiều"), hàng nổi bật
 * phẳng và lưới truyện lấy từ máy chủ (kéo để làm mới, khung xương khi tải lần đầu, bản lưu khi mất mạng). Hàng nổi bật và
 * danh sách cùng theo bảng đang chọn; "Dành cho bạn" kèm lý do gợi ý dưới từng thẻ. Tuyển chọn có sẵn chỉ là phương án cuối.
 * Truyện đã có trong tủ được đánh dấu "Trong tủ".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DiscoverTab(
    state: DiscoverUiState,
    libraryKeys: Set<String>,
    onSelectNovel: (Novel) -> Unit,
    onSelectSort: (DiscoverSort) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {}
) {
    val feed = state.current
    val mode = DiscoverFeeds.mode(feed)
    val shown = DiscoverPresentation.shownSort(state.sort, feed)
    var category by remember { mutableStateOf(DiscoverFallbackCatalog.ALL) }
    val quickFilter = remember { DiscoverQuickFilterState() }
    val gridState = rememberLazyGridState()
    val pullState = rememberPullToRefreshState()
    // Đổi bảng thì danh sách mới phải bắt đầu từ đầu, không đứng nguyên chỗ đã cuộn của bảng trước
    LaunchedEffect(state.sort) { gridState.scrollToItem(0) }

    val carousel = remember(mode, feed, state.sort, libraryKeys) {
        when (mode) {
            DiscoverMode.LIVE -> DiscoverCarousel.items(feed, state.sort, libraryKeys)
            DiscoverMode.FALLBACK -> DiscoverFallbackCatalog.featured.map {
                it.copy(inLibrary = LibraryListLogic.isInLibrary(it.novel, libraryKeys))
            }
            DiscoverMode.LOADING -> emptyList()
        }
    }
    val allLiveNovels = remember(mode, feed) {
        if (mode == DiscoverMode.LIVE) feed.novels.distinctBy { it.id } else emptyList()
    }
    val overnightCount = remember(allLiveNovels, state.overnightIds, feed.overnightIds) {
        allLiveNovels.count { state.isOvernight(it) }
    }
    val translatedCount = remember(allLiveNovels, state.translatedCounts, feed.translatedCounts) {
        allLiveNovels.count { state.translatedOf(it) > 0 }
    }
    val notInLibraryCount = remember(allLiveNovels, libraryKeys) {
        allLiveNovels.count { !LibraryListLogic.isInLibrary(it, libraryKeys) }
    }
    val categories = remember(mode, feed, overnightCount, translatedCount) {
        when (mode) {
            DiscoverMode.LIVE -> buildList {
                add(DiscoverCategoryClassifier.ALL)
                if (overnightCount > 0) add(DiscoverCategoryClassifier.OVERNIGHT)
                if (translatedCount > 0) add(DiscoverCategoryClassifier.AI_TRANSLATED)
                addAll(feed.novels.map { feed.categoryOf(it) }.distinct())
            }
            DiscoverMode.FALLBACK -> DiscoverFallbackCatalog.categories
            DiscoverMode.LOADING -> emptyList()
        }
    }
    LaunchedEffect(categories) {
        if (category !in categories) category = DiscoverCategoryClassifier.ALL
    }
    val novels = remember(
        mode, feed, category, allLiveNovels, libraryKeys,
        state.overnightIds, state.translatedCounts,
        quickFilter.query, quickFilter.onlyOvernight, quickFilter.onlyTranslated, quickFilter.hideInLibrary, quickFilter.localSort
    ) {
        when (mode) {
            DiscoverMode.LIVE -> applyDiscoverQuickFilters(allLiveNovels, category, state, libraryKeys, quickFilter)
            DiscoverMode.FALLBACK -> DiscoverFallbackCatalog.novels(category)
            DiscoverMode.LOADING -> emptyList()
        }
    }

    // Cử chỉ kéo-để-làm-mới nằm ngoài thanh tiêu đề (xem RefreshIndicator): tiêu đề lớn mở ra trước, phần dư mới kéo vòng xoay
    Box(
        modifier = modifier
            .fillMaxSize()
            .pullToRefresh(isRefreshing = state.isRefreshing, state = pullState, onRefresh = onRefresh)
    ) {
        LargeTitleScaffold(
            title = "Khám phá",
            actions = { QuietIconButton(TruyenCloudIcons.Search, "Tìm kiếm", onSearch) }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                    contentPadding = PaddingValues(
                        start = Spacing.pageH,
                        end = Spacing.pageH,
                        top = Spacing.xs,
                        bottom = padding.calculateBottomPadding()
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Luôn cho đổi bảng: mỗi bảng tải riêng nên một bảng lỗi không có nghĩa các bảng kia cũng lỗi
                    item(key = "discover:sorts", span = FullSpan) {
                        DiscoverSortControl(sorts = state.sorts, selected = state.sort, onSelect = onSelectSort)
                    }

                    DiscoverFeeds.notice(feed)?.let { text ->
                        item(key = "discover:notice", span = FullSpan) {
                            DiscoverNotice(
                                text = text,
                                onRetry = onRefresh,
                                tone = if (feed.error != null) NoticeTone.Warning else NoticeTone.Info
                            )
                        }
                    }

                    DiscoverPresentation.hint(state.sort, feed, mode)?.let { text ->
                        item(key = "discover:hint", span = FullSpan) { DiscoverHint(text = text) }
                    }

                    item(key = "discover:featured", span = FullSpan) {
                        if (mode == DiscoverMode.LOADING) {
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                ShimmerBox(width = maxWidth, height = FEATURED_HEIGHT)
                            }
                        } else {
                            // Đổi bảng thì hàng nổi bật về thẻ đầu, không đứng nguyên ở trang đã lướt của bảng trước
                            key(state.sort) {
                                TrendingCarousel(
                                    items = carousel,
                                    onSelectNovel = onSelectNovel,
                                    modifier = Modifier.fullBleed()
                                )
                            }
                        }
                    }

                    if (mode == DiscoverMode.LIVE && allLiveNovels.isNotEmpty()) {
                        item(key = "discover:quickFilter", span = FullSpan) {
                            DiscoverQuickFilterBar(
                                quickFilter = quickFilter,
                                totalCount = allLiveNovels.size,
                                overnightCount = overnightCount,
                                translatedCount = translatedCount,
                                notInLibraryCount = notInLibraryCount,
                                modifier = Modifier.fullBleed()
                            )
                        }
                    }

                    if (mode != DiscoverMode.LOADING && categories.size > 1) {
                        item(key = "discover:categories", span = FullSpan) {
                            CategoryPills(
                                categories = categories,
                                selectedCategory = category,
                                onSelectCategory = { category = it },
                                modifier = Modifier.fullBleed()
                            )
                        }
                    }

                    item(key = "discover:title", span = FullSpan) {
                        SectionHeader(title = DiscoverPresentation.sectionTitle(mode, shown, category, novels.size))
                    }

                    if (mode == DiscoverMode.LOADING) {
                        items(SKELETON_COUNT) { NovelCardSkeleton() }
                    } else {
                        items(novels, key = { it.id }) { novel ->
                            DiscoverNovelCard(
                                novel = novel,
                                // Lý do chỉ có nghĩa với danh sách thật của máy chủ, không phải tuyển chọn có sẵn
                                reason = if (mode == DiscoverMode.LIVE) feed.reasonOf(novel) else "",
                                modifier = Modifier.animateItem(),
                                inLibrary = LibraryListLogic.isInLibrary(novel, libraryKeys),
                                overnightEnabled = mode == DiscoverMode.LIVE && state.isOvernight(novel),
                                translatedChapters = if (mode == DiscoverMode.LIVE) state.translatedOf(novel) else 0,
                                onClick = { onSelectNovel(novel) }
                            )
                        }
                    }
                }

                RefreshIndicator(state = pullState, isRefreshing = state.isRefreshing, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}
