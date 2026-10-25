package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import com.truyencloud.compose.screens.home.imeAboveTabBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.LargeTitleScaffold
import com.truyencloud.compose.navigation.PlatformBackHandler
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Màn hình trung tâm Từ vựng (học thuật tiếng Anh + lặp lại ngắt quãng FSRS), một tab của Home.
 *
 * Bố cục: tiêu đề lớn "Từ vựng" thu gọn khi cuộn ([LargeTitleScaffold]), bộ chọn 4 chế độ ghim bên dưới, rồi nội dung:
 * - [VocabularyBody]: thân màn, gồm NotebookView, FlashcardsView, PracticeView, RealLifeStudioView
 * - [VocabularyOverlays]: tùy chọn phụ, QuizDialog, ContextStoryDialog, PronunciationSheet, ReviewReminderDialog
 * - Tìm kiếm: hàng [VocabularySearchHeader] thay cho thanh tiêu đề, kết quả là danh sách Sổ từ phẳng
 */
@Composable
fun VocabularyScreen(
    viewModel: VocabularyViewModel,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val uiState by viewModel.uiState.collectAsState()
    var isSearchActive by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }

    fun closeSearch() {
        isSearchActive = false
        viewModel.onSearchQueryChange("")
    }
    // Back đóng ô tìm kiếm trước khi rời tab
    PlatformBackHandler(enabled = isSearchActive) { closeSearch() }
    // Mở một từ từ kết quả rồi bấm "Luyện ứng dụng" đổi chế độ: thoát ô tìm kiếm để thấy chế độ mới
    LaunchedEffect(uiState.activeMode) {
        if (isSearchActive && uiState.activeMode != VocabMode.NOTEBOOK) closeSearch()
    }

    Box(modifier = modifier.fillMaxSize().background(colors.bg)) {
        if (isSearchActive) {
            Column(modifier = Modifier.fillMaxSize().imeAboveTabBar()) {
                VocabularySearchHeader(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::onSearchQueryChange,
                    onCancel = ::closeSearch
                )
                NotebookContent(uiState, viewModel, bottomPadding = Spacing.lg, showOverview = false)
            }
        } else {
            LargeTitleScaffold(
                title = "Từ vựng",
                actions = {
                    VocabularyHeaderActions(
                        showSearch = uiState.activeMode == VocabMode.NOTEBOOK,
                        isSyncing = uiState.isSyncing,
                        isSyncSuccess = uiState.isSyncSuccess,
                        onSearch = { isSearchActive = true },
                        onSyncCloud = viewModel::syncWithCloud,
                        onMore = { showMoreSheet = true }
                    )
                }
            ) { padding ->
                VocabularyBody(
                    uiState = uiState,
                    viewModel = viewModel,
                    bottomPadding = padding.calculateBottomPadding()
                )
            }
        }

        VocabularyOverlays(
            uiState = uiState,
            viewModel = viewModel,
            showMoreSheet = showMoreSheet,
            onDismissMore = { showMoreSheet = false }
        )
    }
}
