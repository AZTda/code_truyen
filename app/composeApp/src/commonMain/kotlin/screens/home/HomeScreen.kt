package com.truyencloud.compose.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.AppBottomNavBar
import com.truyencloud.compose.components.NavTab
import com.truyencloud.compose.navigation.PlatformBackHandler
import com.truyencloud.compose.screens.insights.InsightsViewModel
import com.truyencloud.compose.screens.settings.SettingsViewModel
import com.truyencloud.compose.screens.vocabulary.VocabularyViewModel
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.data.Novel

/**
 * Màn hình chính: năm tab dưới một thanh tab chuẩn. KHÔNG tự vẽ thanh tiêu đề hay chừa thanh trạng thái: mỗi tab là một
 * LargeTitleScaffold riêng (Tủ sách, Khám phá ở đây; Từ vựng, Thống kê, Cài đặt ở màn của chúng) nên tiêu đề lớn thu lại
 * khi cuộn và thanh bắt đầu ngay mép trên màn hình. Lớp tìm kiếm phủ lên cả vùng tab, thanh tab dưới vẫn hiện.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    vocabularyViewModel: VocabularyViewModel? = null,
    settingsViewModel: SettingsViewModel? = null,
    insightsViewModel: InsightsViewModel? = null,
    onSelectNovel: (Novel) -> Unit,
    onContinueReading: ((Novel) -> Unit)? = null,
    onLogout: () -> Unit = {},
    miniPlayer: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val uiState by viewModel.uiState.collectAsState()

    val selecting = uiState.currentTab == NavTab.BOOKSHELF && !uiState.isSearchActive && uiState.selection.isActive
    val selectedNovels = remember(uiState.shelfNovels, uiState.selection) {
        uiState.shelfNovels.filter { it.id in uiState.selection }
    }

    // Back thoát chế độ chọn nhiều trước, rồi mới đóng tìm kiếm
    PlatformBackHandler(enabled = uiState.isSearchActive) { viewModel.closeSearch() }
    PlatformBackHandler(enabled = selecting) { viewModel.clearSelection() }

    val bookshelfActions = remember(viewModel, vocabularyViewModel, onSelectNovel, onContinueReading) {
        BookshelfActions(
            onSelectShelf = viewModel::onSelectShelf,
            onSelectSort = viewModel::onSelectSort,
            onToggleFavorites = viewModel::onToggleFavoritesOnly,
            onToggleOvernightOnly = viewModel::onToggleOvernightOnly,
            onToggleAiTranslatedOnly = viewModel::onToggleAiTranslatedOnly,
            onOpenNovel = onSelectNovel,
            onContinueReading = onContinueReading,
            onLongPress = viewModel::onNovelLongPress,
            onToggleSelection = viewModel::onToggleSelection,
            onExplore = { viewModel.selectTab(NavTab.DISCOVER) },
            onImport = viewModel::openImportDialog,
            onRetry = viewModel::reloadBookshelf,
            onRefresh = viewModel::refreshBookshelf,
            onDismissMessage = viewModel::dismissLibraryMessage,
            onSpeakWord = { word -> vocabularyViewModel?.speakWord(word) },
            onOpenVocab = { viewModel.selectTab(NavTab.VOCABULARY) },
            onSearch = viewModel::openSearch,
            onDismissImportMessage = viewModel::dismissImportMessage,
            selection = SelectionActions(
                onClear = viewModel::clearSelection,
                onSelectAll = viewModel::selectAllVisible,
                onFavorite = viewModel::toggleFavoriteForSelection,
                onMoveShelf = viewModel::openLibrarySheet,
                onDelete = viewModel::requestRemoval
            )
        )
    }

    Box(modifier = modifier.fillMaxSize().background(colors.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                Crossfade(
                    targetState = uiState.isSearchActive,
                    animationSpec = tween(AppAnimations.SHORT_MS),
                    label = "homeSearch"
                ) { searching ->
                    if (searching) {
                        HomeSearchLayer(uiState = uiState, viewModel = viewModel, onSelectNovel = onSelectNovel)
                    } else {
                        HomeTabContent(
                            uiState = uiState,
                            viewModel = viewModel,
                            bookshelfActions = bookshelfActions,
                            vocabularyViewModel = vocabularyViewModel,
                            settingsViewModel = settingsViewModel,
                            insightsViewModel = insightsViewModel,
                            onSelectNovel = onSelectNovel,
                            onLogout = onLogout
                        )
                    }
                }
            }

            miniPlayer?.invoke()

            AppBottomNavBar(
                selectedTab = uiState.currentTab,
                onTabSelected = { tab ->
                    if (uiState.isSearchActive) viewModel.closeSearch()
                    viewModel.selectTab(tab)
                }
            )
        }

        HomeOverlays(uiState = uiState, viewModel = viewModel, selectedNovels = selectedNovels, selecting = selecting)
    }
}
