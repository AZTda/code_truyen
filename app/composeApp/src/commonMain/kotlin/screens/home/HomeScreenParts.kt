package com.truyencloud.compose.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.LibraryActionSheet
import com.truyencloud.compose.components.LibraryRemoveDialog
import com.truyencloud.compose.components.NavTab
import com.truyencloud.compose.platform.PickedBookFile
import com.truyencloud.compose.screens.insights.InsightsScreen
import com.truyencloud.compose.screens.insights.InsightsViewModel
import com.truyencloud.compose.screens.settings.SettingsScreen
import com.truyencloud.compose.screens.settings.SettingsViewModel
import com.truyencloud.compose.screens.vocabulary.VocabularyScreen
import com.truyencloud.compose.screens.vocabulary.VocabularyViewModel
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayTitle

/**
 * Nội dung của tab đang chọn. Tủ sách và Khám phá là của màn này; ba tab còn lại là màn gốc của chúng, gọi với tham số
 * như cũ và tự lo tiêu đề lớn lẫn vùng thanh trạng thái (host không chừa gì ở trên).
 */
@Composable
internal fun HomeTabContent(
    uiState: HomeUiState,
    viewModel: HomeViewModel,
    bookshelfActions: BookshelfActions,
    vocabularyViewModel: VocabularyViewModel?,
    settingsViewModel: SettingsViewModel?,
    insightsViewModel: InsightsViewModel?,
    onSelectNovel: (Novel) -> Unit,
    onLogout: () -> Unit
) {
    when (uiState.currentTab) {
        NavTab.BOOKSHELF -> BookshelfTab(state = uiState, actions = bookshelfActions)
        NavTab.DISCOVER -> DiscoverTab(
            state = uiState.discover,
            libraryKeys = uiState.libraryKeys,
            onSelectNovel = onSelectNovel,
            onSelectSort = viewModel::onSelectDiscoverSort,
            onRefresh = viewModel::refreshDiscover,
            onSearch = viewModel::openSearch
        )
        NavTab.VOCABULARY -> if (vocabularyViewModel != null) VocabularyScreen(viewModel = vocabularyViewModel)
        NavTab.INSIGHTS -> if (insightsViewModel != null) InsightsScreen(viewModel = insightsViewModel)
        NavTab.SETTINGS -> if (settingsViewModel != null) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onLogout = onLogout
            )
        }
    }
}

/** Lớp tìm kiếm toàn màn hình: ô tìm kiếm có nút "Hủy" ở trên cùng, kết quả / gợi ý bên dưới. */
@Composable
internal fun HomeSearchLayer(
    uiState: HomeUiState,
    viewModel: HomeViewModel,
    onSelectNovel: (Novel) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(LocalCustomColors.current.bg)) {
        HomeSearchHeader(
            query = uiState.searchQuery,
            onQueryChange = viewModel::onSearchQueryChange,
            onSubmit = viewModel::submitSearch,
            onClose = viewModel::closeSearch
        )
        SearchOverlayView(
            modifier = Modifier.weight(1f),
            searchQuery = uiState.searchQuery,
            isSearching = uiState.isSearching,
            searchResults = uiState.searchResults,
            searchError = uiState.searchError,
            selectedSource = uiState.selectedSource,
            onSelectSource = viewModel::onSelectSource,
            onSelectNovel = { novel ->
                viewModel.onSearchResultPicked()
                onSelectNovel(novel)
            },
            // Liên kết / "Thử lại": tìm lại từ khoá đang có, không ghi vào lịch sử
            onQuickSearch = { query ->
                viewModel.onSearchQueryChange(query)
                viewModel.performSearch()
            },
            suggestions = uiState.suggestions,
            liveSuggestions = uiState.liveSuggestions,
            onSuggestionClick = viewModel::onSuggestionClick,
            onClearHistory = viewModel::clearSearchHistory,
            libraryKeys = uiState.libraryKeys,
            discover = uiState.discover
        )
    }
}

/** Lớp phủ: tờ hành động, xác nhận xóa, nạp tệp, hướng dẫn chào mừng. */
@Composable
internal fun HomeOverlays(
    uiState: HomeUiState,
    viewModel: HomeViewModel,
    selectedNovels: List<Novel>,
    selecting: Boolean
) {
    if (selecting && uiState.showLibrarySheet && selectedNovels.isNotEmpty()) {
        LibraryActionSheet(
            novels = selectedNovels,
            onDismiss = viewModel::closeLibrarySheet,
            onMoveToShelf = viewModel::moveSelectionToShelf,
            onToggleFavorite = viewModel::toggleFavoriteForSelection,
            onRemove = viewModel::requestRemoval,
            onSelectMore = if (selectedNovels.size < uiState.shelfNovels.size) viewModel::closeLibrarySheet else null
        )
    }

    uiState.pendingRemoval?.let { pending ->
        LibraryRemoveDialog(
            titles = pending.novels.map { it.displayTitle },
            offlineBytes = pending.offlineBytes,
            onConfirm = viewModel::confirmRemoval,
            onDismiss = viewModel::cancelRemoval
        )
    }

    if (uiState.showImportDialog) {
        ImportBookDialog(
            isImporting = uiState.isImporting,
            onImport = viewModel::importNovel,
            onImportFile = { title, author, file: PickedBookFile -> viewModel.importNovelFile(title, author, file) },
            onDismiss = viewModel::closeImportDialog,
            errorMessage = uiState.importMessage
        )
    }

    if (uiState.showWelcomeGuide) {
        WelcomeGuideSheet(onDismiss = viewModel::closeWelcomeGuide)
    }
}
