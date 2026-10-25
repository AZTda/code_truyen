package com.truyencloud.compose.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.NovelCard
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.library.LibraryFormat
import com.truyencloud.shared.library.LibraryListLogic
import com.truyencloud.shared.library.LibrarySort

/** Phần thân của lưới: khung xương khi đang nạp, lỗi, trạng thái trống hoặc các bìa truyện. */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyGridScope.bookshelfBody(state: HomeUiState, actions: BookshelfActions) {
    val selecting = state.selection.isActive
    when {
        state.isLoadingBookshelf -> item(key = "home:loading", span = FullSpan) {
            BookshelfLoadingState()
        }
        state.bookshelfError != null && state.shelfNovels.isEmpty() -> item(key = "home:error", span = FullSpan) {
            BookshelfErrorState(message = state.bookshelfError, onRetry = actions.onRetry)
        }
        state.shelfNovels.isEmpty() -> item(key = "home:empty", span = FullSpan) {
            val activeFilter = state.favoritesOnly || state.overnightOnly || state.aiTranslatedOnly
            LibraryEmptyState(
                shelf = state.selectedShelf,
                libraryIsEmpty = state.allNovelsCount == 0,
                favoritesOnly = activeFilter,
                onExplore = actions.onExplore,
                onImport = actions.onImport,
                onClearFavoritesFilter = {
                    if (state.favoritesOnly) actions.onToggleFavorites()
                    if (state.overnightOnly) actions.onToggleOvernightOnly()
                    if (state.aiTranslatedOnly) actions.onToggleAiTranslatedOnly()
                }
            )
        }
        else -> items(state.shelfNovels, key = { it.id }) { novel ->
            NovelCard(
                novel = novel,
                modifier = Modifier.fillMaxWidth().animateItem(),
                selectionMode = selecting,
                selected = novel.id in state.selection,
                showFavorite = true,
                overnightEnabled = state.discover.isOvernight(novel),
                translatedChapters = state.discover.translatedOf(novel),
                footnote = if (state.librarySort == LibrarySort.UNREAD) {
                    LibraryFormat.unreadLabel(LibraryListLogic.unreadChapters(novel))
                } else null,
                onClick = { if (selecting) actions.onToggleSelection(novel) else actions.onOpenNovel(novel) },
                onLongClick = { actions.onLongPress(novel) }
            )
        }
    }
}

/**
 * Thông báo nổi phía trên danh sách: nằm trong lưới thì bị cuộn khuất khi người dùng đang ở giữa danh sách. Khi hộp
 * thoại nạp tệp đang mở, lỗi đã hiện ngay trong hộp thoại nên không lặp lại ở đây.
 */
@Composable
internal fun BoxScope.BookshelfNotices(state: HomeUiState, actions: BookshelfActions) {
    val library = state.libraryMessage
    val import = state.importMessage?.takeIf { it.isNotBlank() && !state.showImportDialog }
    if (library == null && import == null) return
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(horizontal = Spacing.pageH, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        library?.let { FeedbackBanner(text = it, tone = state.libraryMessageTone, onDismiss = actions.onDismissMessage) }
        import?.let {
            FeedbackBanner(
                text = it,
                tone = if (state.importMessageIsError) NoticeTone.Error else NoticeTone.Success,
                onDismiss = actions.onDismissImportMessage
            )
        }
    }
}
