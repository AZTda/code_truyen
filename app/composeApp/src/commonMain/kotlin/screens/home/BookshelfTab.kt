package com.truyencloud.compose.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.LargeTitleScaffold
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.ShelfTabs
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.Spacing

/**
 * Tab Tủ sách: tiêu đề lớn "Tủ sách" (tìm kiếm, nạp tệp, sắp xếp), hàng "Đang đọc", từ hôm nay, ba kệ và lưới bìa.
 * Nhấn giữ một bìa để chọn + mở tờ hành động; khi đang chọn thì chạm để chọn thêm và thanh tiêu đề đổi thành thanh chọn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfTab(
    state: HomeUiState,
    actions: BookshelfActions,
    modifier: Modifier = Modifier
) {
    val selecting = state.selection.isActive
    var showSort by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()
    // Giữ vị trí cuộn khi chuyển giữa tiêu đề lớn và thanh chọn nhiều (hai cây giao diện khác nhau): nhấn giữ một bìa
    // ở giữa kệ không được làm danh sách nhảy về đầu
    val gridState = rememberLazyGridState()

    // Cử chỉ kéo-để-làm-mới nằm ngoài thanh tiêu đề (xem RefreshIndicator): tiêu đề lớn mở ra trước, phần dư mới kéo vòng xoay
    Box(
        modifier = modifier
            .fillMaxSize()
            .pullToRefresh(isRefreshing = state.isSyncingLibrary, state = pullState, onRefresh = actions.onRefresh)
    ) {
        if (selecting) {
            Column(modifier = Modifier.fillMaxSize()) {
                BookshelfSelectionBar(state = state, actions = actions)
                BookshelfGrid(state, actions, gridState, PaddingValues(bottom = Spacing.lg), pullState, Modifier.weight(1f))
            }
        } else {
            LargeTitleScaffold(
                title = "Tủ sách",
                actions = {
                    QuietIconButton(TruyenCloudIcons.Search, "Tìm kiếm", actions.onSearch)
                    QuietIconButton(TruyenCloudIcons.HistoryTimeline, "Nhật ký đọc gần đây", { showHistory = true })
                    QuietIconButton(TruyenCloudIcons.Import, "Nạp tệp sách", actions.onImport)
                    QuietIconButton(TruyenCloudIcons.Sort, "Sắp xếp và lọc", { showSort = true })
                }
            ) { padding ->
                BookshelfGrid(state, actions, gridState, padding, pullState, Modifier.fillMaxSize(), onOpenHistory = { showHistory = true })
            }
        }
    }

    if (showSort) {
        LibrarySortSheet(
            sort = state.librarySort,
            favoritesOnly = state.favoritesOnly,
            favoriteCount = state.favoriteCount,
            overnightOnly = state.overnightOnly,
            overnightCount = state.overnightCount,
            aiTranslatedOnly = state.aiTranslatedOnly,
            aiTranslatedCount = state.aiTranslatedCount,
            onSelectSort = actions.onSelectSort,
            onToggleFavorites = actions.onToggleFavorites,
            onToggleOvernightOnly = actions.onToggleOvernightOnly,
            onToggleAiTranslatedOnly = actions.onToggleAiTranslatedOnly,
            onDismiss = { showSort = false }
        )
    }

    if (showHistory) {
        ReadingHistorySheet(
            recentNovels = state.recentNovels,
            onResumeHistoryItem = { item ->
                val target = state.recentNovels.firstOrNull { it.id == item.novelId }
                    ?: state.shelfNovels.firstOrNull { it.id == item.novelId }
                    ?: com.truyencloud.shared.data.Novel(
                        id = item.novelId,
                        title = item.novelTitle,
                        titleVi = item.novelTitle,
                        author = item.author,
                        coverUrl = item.coverUrl,
                        lastReadChapter = item.chapterNumber
                    )
                val updated = target.copy(lastReadChapter = item.chapterNumber)
                (actions.onContinueReading ?: actions.onOpenNovel).invoke(updated)
            },
            onDismiss = { showHistory = false }
        )
    }
}

@Composable
private fun BookshelfSelectionBar(state: HomeUiState, actions: BookshelfActions) {
    val allSelected = state.selection.isAllSelected(state.shelfNovels.map { it.id })
    val allFavorite = state.shelfNovels.filter { it.id in state.selection }.let { chosen -> chosen.isNotEmpty() && chosen.all { it.isFavorite } }
    LibrarySelectionBar(
        count = state.selection.count,
        allSelected = allSelected,
        allFavorite = allFavorite,
        onClose = actions.selection.onClear,
        onToggleSelectAll = { if (allSelected) actions.selection.onClear() else actions.selection.onSelectAll() },
        onFavorite = actions.selection.onFavorite,
        onMoveShelf = actions.selection.onMoveShelf,
        onDelete = actions.selection.onDelete
    )
}

/** Lưới của tab: chiếm trọn vùng dưới thanh tiêu đề, kèm vòng xoay kéo-để-làm-mới và dải báo nổi ở mép trên. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BookshelfGrid(
    state: HomeUiState,
    actions: BookshelfActions,
    gridState: LazyGridState,
    padding: PaddingValues,
    pullState: PullToRefreshState,
    modifier: Modifier,
    onOpenHistory: (() -> Unit)? = null
) {
    val selecting = state.selection.isActive
    Box(modifier = modifier.clipToBounds()) {
        LazyVerticalGrid(
            state = gridState,
            // 96dp = bề rộng bìa: màn 360dp với lề 20dp cho đúng ba cột
            columns = GridCells.Adaptive(minSize = Spacing.coverWidth),
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
            // Đang chọn nhiều thì chỉ giữ kệ và lưới truyện để khỏi rối
            if (!selecting) {
                if (state.recentNovels.isNotEmpty()) {
                    item(key = "home:recent", span = FullSpan) {
                        ContinueReadingSection(
                            novels = state.recentNovels,
                            onOpen = actions.onContinueReading ?: actions.onOpenNovel,
                            onOpenHistory = onOpenHistory
                        )
                    }
                }
                state.wordOfTheDay?.let { word ->
                    item(key = "home:word", span = FullSpan) {
                        WordOfTheDayCard(word = word, onSpeak = actions.onSpeakWord, onClick = actions.onOpenVocab)
                    }
                }
            }

            item(key = "home:shelves", span = FullSpan) {
                ShelfTabs(
                    selectedShelf = state.selectedShelf,
                    onSelectShelf = actions.onSelectShelf,
                    counts = state.shelfCounts
                )
            }

            // Chip lọc nhanh dưới kệ: yêu thích, đang dịch ngầm ban đêm, hoặc đã có chương dịch AI
            val showFilterRow = state.favoritesOnly ||
                state.overnightOnly || state.overnightCount > 0 ||
                state.aiTranslatedOnly || state.aiTranslatedCount > 0
            if (showFilterRow) {
                item(key = "home:favorites", span = FullSpan) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        if (state.favoritesOnly) {
                            FilterChip8(
                                label = "Yêu thích",
                                selected = true,
                                onClick = actions.onToggleFavorites,
                                leadingIcon = TruyenCloudIcons.HeartFilled,
                                trailingText = state.favoriteCount.takeIf { it > 0 }?.toString()
                            )
                        }
                        if (state.overnightOnly || state.overnightCount > 0) {
                            FilterChip8(
                                label = "Dịch ngầm",
                                selected = state.overnightOnly,
                                onClick = actions.onToggleOvernightOnly,
                                leadingIcon = TruyenCloudIcons.MoonStar,
                                trailingText = state.overnightCount.takeIf { it > 0 }?.toString()
                            )
                        }
                        if (state.aiTranslatedOnly || state.aiTranslatedCount > 0) {
                            FilterChip8(
                                label = "Đã dịch AI",
                                selected = state.aiTranslatedOnly,
                                onClick = actions.onToggleAiTranslatedOnly,
                                leadingIcon = TruyenCloudIcons.Zap,
                                trailingText = state.aiTranslatedCount.takeIf { it > 0 }?.toString()
                            )
                        }
                    }
                }
            }

            bookshelfBody(state, actions)
        }

        RefreshIndicator(state = pullState, isRefreshing = state.isSyncingLibrary, modifier = Modifier.align(Alignment.TopCenter))
        BookshelfNotices(state = state, actions = actions)
    }
}
