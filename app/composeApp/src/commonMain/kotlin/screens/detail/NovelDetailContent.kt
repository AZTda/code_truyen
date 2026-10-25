package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import com.truyencloud.compose.components.SmallTitleTopBar
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayTitle

/** Các hành động của trang truyện, gom một chỗ để [NovelDetailContent] không phải nhận hàng chục tham số lẻ. */
@Immutable
internal class DetailCallbacks(
    val onBack: () -> Unit,
    val onStartReading: () -> Unit,
    val onStartTts: () -> Unit,
    val onDownload: () -> Unit,
    val onAddToLibrary: () -> Unit,
    val onRemoveFromLibrary: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onCheckForUpdates: () -> Unit,
    val onRetry: () -> Unit,
    val onChapterClick: (Chapter) -> Unit,
    val onDismissRestoredMessage: () -> Unit,
    val onDismissCheckMessage: () -> Unit,
    val onExportEpub: () -> Unit = {},
    val onOpenGlossary: () -> Unit = {},
    val onOpenVoicePicker: () -> Unit = {},
    val onOpenVocabSheet: () -> Unit = {}
)

/**
 * Bố cục trang truyện: thanh trên nhỏ (chevron quay lại, tên truyện serif ở giữa, nút quét chương mới) rồi MỘT danh sách
 * cuộn duy nhất — đầu trang (bìa + thông tin), nút đọc + hành động phụ, thông báo, ô chọn Giới thiệu / Mục lục, và nội dung
 * tab. Cuộn cả trang nên mục lục dài được dùng gần hết màn hình thay vì bị kẹp dưới các hàng nút cố định.
 *
 * Đáy danh sách chừa thanh điều hướng hệ thống; `imePadding` để bàn phím không che ô tìm chương.
 */
@Composable
internal fun NovelDetailContent(
    novel: Novel,
    state: NovelDetailUiState,
    studioState: DetailStudioUiState,
    selectedTab: DetailTab,
    onSelectTab: (DetailTab) -> Unit,
    filter: ChapterFilterState,
    callbacks: DetailCallbacks,
    miniPlayer: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val serverTranslatedSet = remember(state.serverTranslatedChapters) { state.serverTranslatedChapters.toHashSet() }
    val visibleChapters = rememberVisibleChapters(state.chapters, state.chapterFlags, filter, serverTranslatedSet)
    val downloadedCount = remember(state.chapterFlags) { state.chapterFlags.values.count { it.isOfflineReady } }
    val isInitialLoading = state.isLoading && state.chapters.isEmpty()
    val hasLoadError = state.errorMessage != null && state.chapters.isEmpty()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val jumpToCurrentChapter: () -> Unit = {
        val targetCh = novel.lastReadChapter
        if (targetCh > 1) {
            if (filter.query.isNotBlank() || filter.onlyDownloaded) {
                filter.query = ""
                filter.onlyDownloaded = false
            }
            val fullList = if (!filter.ascending) state.chapters.reversed() else state.chapters
            val idxInChapters = fullList.indexOfFirst { it.chapterNumber == targetCh }
            if (idxInChapters >= 0) {
                val hasBatchStrip = state.batchProgress != null && state.batchProgress < 1f && !state.showBatchDownloadDialog
                val hasNotices = hasDetailNotices(state.restoredProgressMessage, state.checkUpdateMessage, state.libraryMessage)
                val prefixItems = 1 +
                    (if (state.isOfflineMode) 1 else 0) +
                    1 +
                    (if (hasBatchStrip) 1 else 0) +
                    (if (hasNotices) 1 else 0) +
                    1 +
                    (if (state.chapters.isNotEmpty()) 1 else 0)
                scope.launch {
                    listState.scrollToItem(prefixItems + idxInChapters)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.bg)) {
        SmallTitleTopBar(
            title = novel.displayTitle,
            onBack = callbacks.onBack,
            serifTitle = true,
            actions = {
                if (!isInitialLoading && !hasLoadError) {
                    ScanChaptersAction(isChecking = state.isCheckingUpdates, onClick = callbacks.onCheckForUpdates)
                }
            }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().imePadding(),
            contentPadding = PaddingValues(bottom = bottomInset + Spacing.xl)
        ) {
            item(key = "header") {
                NovelHeader(
                    novel = novel,
                    modifier = Modifier.padding(horizontal = Spacing.pageH, vertical = Spacing.lg),
                    downloadedCount = downloadedCount,
                    serverTranslatedCount = state.serverTranslatedCount,
                    isOvernightEnabled = state.isOvernightEnabled
                )
            }
            when {
                isInitialLoading -> item(key = "loading") { DetailLoadingCard() }
                hasLoadError -> item(key = "error") {
                    NovelDetailErrorView(
                        errorMessage = state.errorMessage,
                        onBack = callbacks.onBack,
                        onRetry = callbacks.onRetry,
                        modifier = Modifier.fillParentMaxHeight(0.6f)
                    )
                }
                else -> loadedContent(
                    novel = novel,
                    state = state,
                    studioState = studioState,
                    downloadedCount = downloadedCount,
                    selectedTab = selectedTab,
                    onSelectTab = onSelectTab,
                    filter = filter,
                    visibleChapters = visibleChapters,
                    serverTranslated = serverTranslatedSet,
                    callbacks = callbacks,
                    onJumpToCurrentChapter = jumpToCurrentChapter
                )
            }
        }
        miniPlayer?.invoke()
    }
}

/** Phần dưới đầu trang khi đã có mục lục (hoặc đã có tiến độ đọc để tiếp tục). */
private fun LazyListScope.loadedContent(
    novel: Novel,
    state: NovelDetailUiState,
    studioState: DetailStudioUiState,
    downloadedCount: Int,
    selectedTab: DetailTab,
    onSelectTab: (DetailTab) -> Unit,
    filter: ChapterFilterState,
    visibleChapters: List<Chapter>,
    serverTranslated: Set<Int>,
    callbacks: DetailCallbacks,
    onJumpToCurrentChapter: () -> Unit
) {
    if (state.isOfflineMode) {
        item(key = "offline") {
            DetailOfflineBanner(modifier = Modifier.padding(start = Spacing.pageH, end = Spacing.pageH, bottom = Spacing.md))
        }
    }

    item(key = "actions") {
        NovelActionBar(
            lastReadChapter = novel.lastReadChapter,
            hasChapters = state.chapters.isNotEmpty() || novel.lastReadChapter > 0,
            onStartReading = callbacks.onStartReading,
            onStartTts = callbacks.onStartTts,
            onDownload = callbacks.onDownload,
            modifier = Modifier.padding(horizontal = Spacing.pageH),
            libraryLoaded = state.libraryLoaded,
            isInLibrary = state.isInLibrary,
            isFavorite = state.isFavorite,
            shelfName = novel.shelfName,
            onAddToLibrary = callbacks.onAddToLibrary,
            onRemoveFromLibrary = callbacks.onRemoveFromLibrary,
            onToggleFavorite = callbacks.onToggleFavorite
        )
        DetailStudioBar(
            epubReadyCount = maxOf(state.serverTranslatedCount, downloadedCount),
            glossaryCount = studioState.glossaryCount,
            voiceName = studioState.activeVoice.name,
            novelVocabCount = studioState.novelWords.size,
            totalVocabCount = studioState.allWords.size,
            onExportEpub = callbacks.onExportEpub,
            onOpenGlossary = callbacks.onOpenGlossary,
            onOpenVoicePicker = callbacks.onOpenVoicePicker,
            onOpenVocabSheet = callbacks.onOpenVocabSheet,
            modifier = Modifier.padding(start = Spacing.pageH, end = Spacing.pageH, top = Spacing.sm)
        )
    }

    // Tờ tải hàng loạt đã đóng nhưng tác vụ còn chạy: một dải tiến độ mảnh để biết nó vẫn đang làm việc
    val batchProgress = state.batchProgress
    if (batchProgress != null && batchProgress < 1f && !state.showBatchDownloadDialog) {
        item(key = "batchProgress") {
            BatchProgressStrip(
                progress = batchProgress,
                onClick = callbacks.onDownload,
                modifier = Modifier.padding(start = Spacing.pageH, end = Spacing.pageH, top = Spacing.md)
            )
        }
    }

    if (hasDetailNotices(state.restoredProgressMessage, state.checkUpdateMessage, state.libraryMessage)) {
        item(key = "notices") {
            DetailNotices(
                restoredMessage = state.restoredProgressMessage,
                checkMessage = state.checkUpdateMessage,
                libraryMessage = state.libraryMessage,
                onDismissRestored = callbacks.onDismissRestoredMessage,
                onDismissCheck = callbacks.onDismissCheckMessage,
                modifier = Modifier.padding(start = Spacing.pageH, end = Spacing.pageH, top = Spacing.md)
            )
        }
    }

    item(key = "tabs") {
        DetailTabSwitcher(
            selectedTab = selectedTab,
            chapterCount = state.chapters.size,
            onSelect = onSelectTab,
            modifier = Modifier.padding(horizontal = Spacing.pageH, vertical = Spacing.md)
        )
    }

    when (selectedTab) {
        DetailTab.SYNOPSIS -> item(key = "synopsis") {
            NovelSynopsisSection(
                description = novel.description,
                modifier = Modifier.padding(horizontal = Spacing.pageH, vertical = Spacing.sm)
            )
        }
        DetailTab.CHAPTERS -> detailChaptersTab(
            all = state.chapters,
            visible = visibleChapters,
            flags = state.chapterFlags,
            filter = filter,
            onChapterClick = callbacks.onChapterClick,
            serverTranslated = serverTranslated,
            currentChapter = novel.lastReadChapter,
            onJumpToCurrentChapter = onJumpToCurrentChapter
        )
    }
}
