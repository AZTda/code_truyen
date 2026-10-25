package com.truyencloud.compose.screens.detail

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.LibraryActionSheet
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayTitle
import kotlinx.coroutines.delay

/**
 * Trang chi tiết truyện: nối ViewModel với giao diện ([NovelDetailContent]) và giữ các hộp thoại (tải hàng loạt, gỡ khỏi
 * tủ). Chữ ký công khai không đổi.
 */
@Composable
fun NovelDetailScreen(
    novel: Novel,
    viewModel: NovelDetailViewModel,
    onBack: () -> Unit,
    onReadChapter: (Novel, Chapter) -> Unit,
    onStartTts: ((Novel, Chapter) -> Unit)? = null,
    miniPlayer: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val studioState by viewModel.studio.state.collectAsState()
    // `novel` truyền vào có thể là bản cũ giữ trong AppNav; bản của ViewModel luôn theo hàng Room
    // nên "Đọc tiếp • Ch.X" đúng sau khi đọc xong rồi quay lại.
    val activeNovel = uiState.novel ?: novel

    LaunchedEffect(novel.id) {
        if (uiState.novel?.id != novel.id) {
            viewModel.loadNovel(novel)
        }
    }

    // Chỉ bám theo Room khi màn hình đang hiển thị (đang ở trình đọc thì ngừng hẳn).
    DisposableEffect(novel.id) {
        viewModel.onScreenShown()
        onDispose { viewModel.onScreenHidden() }
    }

    LaunchedEffect(uiState.libraryMessage) {
        if (uiState.libraryMessage != null) {
            delay(3_000)
            viewModel.dismissLibraryMessage()
        }
    }

    var selectedTab by remember(novel.id) { mutableStateOf(DetailTab.SYNOPSIS) }
    var showShelfSheet by remember(novel.id) { mutableStateOf(false) }
    val chapterFilter = remember(novel.id) { ChapterFilterState() }

    // Mở trang chi tiết hay bấm đọc KHÔNG tự thêm vào tủ / yêu thích: đọc lần đầu sẽ tự lưu tiến độ
    // (tạo hàng trong tủ), còn thêm vào tủ và yêu thích là thao tác chủ động.
    val callbacks = DetailCallbacks(
        onBack = onBack,
        onStartReading = { viewModel.readTarget()?.let { onReadChapter(activeNovel, it) } },
        onStartTts = { viewModel.readTarget()?.let { onStartTts?.invoke(activeNovel, it) ?: onReadChapter(activeNovel, it) } },
        onDownload = viewModel::openBatchDownloadDialog,
        onAddToLibrary = viewModel::addToLibrary,
        onRemoveFromLibrary = {
            if (uiState.isInLibrary) {
                showShelfSheet = true
            } else {
                viewModel.askRemoveFromLibrary()
            }
        },
        onToggleFavorite = viewModel::toggleFavorite,
        onCheckForUpdates = viewModel::checkForUpdates,
        onRetry = viewModel::retryLoad,
        onChapterClick = { chapter -> onReadChapter(activeNovel, chapter) },
        onDismissRestoredMessage = viewModel::dismissRestoredProgressMessage,
        onDismissCheckMessage = viewModel::dismissCheckUpdateMessage,
        onExportEpub = {
            if (uiState.serverTranslatedCount > 0 || uiState.downloadedChapterNumbers.isNotEmpty()) {
                viewModel.exportEpub()
                viewModel.openBatchDownloadDialog()
            } else {
                viewModel.openBatchDownloadDialog()
            }
        },
        onOpenGlossary = viewModel.studio::openGlossary,
        onOpenVoicePicker = viewModel.studio::openVoicePicker,
        onOpenVocabSheet = viewModel.studio::openVocabSheet
    )

    NovelDetailContent(
        novel = activeNovel,
        state = uiState,
        studioState = studioState,
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        filter = chapterFilter,
        callbacks = callbacks,
        miniPlayer = miniPlayer,
        modifier = modifier
    )

    if (showShelfSheet && uiState.isInLibrary) {
        LibraryActionSheet(
            novels = listOf(activeNovel),
            onDismiss = { showShelfSheet = false },
            onMoveToShelf = { shelf ->
                showShelfSheet = false
                viewModel.moveToShelf(shelf)
            },
            onToggleFavorite = {
                showShelfSheet = false
                viewModel.toggleFavorite()
            },
            onRemove = {
                showShelfSheet = false
                viewModel.askRemoveFromLibrary()
            }
        )
    }

    // Tải / dịch offline hàng loạt
    if (uiState.showBatchDownloadDialog) {
        BatchDownloadDialog(
            novelTitle = activeNovel.displayTitle,
            currentChapter = activeNovel.lastReadChapter,
            totalChapters = uiState.chapters.size,
            onStartBatch = viewModel::startBatchDownload,
            onStartOvernight = viewModel::startOvernightTranslation,
            onStopOvernight = viewModel::stopOvernightTranslation,
            onSyncAllServerTranslated = viewModel::syncAllServerTranslated,
            onExportEpub = viewModel::exportEpub,
            studioState = studioState,
            onOpenVoicePicker = viewModel.studio::openVoicePicker,
            onPrerenderAudio = { fromCh, toCh -> viewModel.studio.prerenderAudioRange(activeNovel.id, fromCh, toCh) },
            serverTranslatedCount = uiState.serverTranslatedCount,
            downloadedCount = uiState.downloadedChapterNumbers.size,
            isOvernightEnabled = uiState.isOvernightEnabled,
            onDismiss = viewModel::closeBatchDownloadDialog,
            isQueuing = uiState.isBatchQueuing,
            successMessage = uiState.batchMessage,
            batchProgress = uiState.batchProgress,
            requiresLogin = uiState.batchRequiresLogin
        )
    }

    if (uiState.showRemoveConfirm) {
        RemoveFromLibraryDialog(
            novelTitle = activeNovel.displayTitle,
            onConfirm = viewModel::removeFromLibrary,
            onDismiss = viewModel::dismissRemoveConfirm
        )
    }

    DetailStudioModals(
        studioState = studioState,
        studio = viewModel.studio,
        onRetranslateOrBatch = viewModel::openBatchDownloadDialog,
        onOpenReaderForLookup = { viewModel.readTarget()?.let { onReadChapter(activeNovel, it) } }
    )
}
