package com.truyencloud.compose.screens.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.TranslationProgressCard

/**
 * Phần thân màn đọc: chọn giữa màn chờ / thẻ tiến trình dịch / thẻ lỗi dịch / lỗi tải chương / nội dung chương.
 *
 * Khi CHƯA có chữ nào để đọc thì trạng thái hiện ở giữa màn hình (lớp [ReaderStatusLayer] chỉ dành cho lúc đã có chữ).
 * Lỗi dịch có ưu tiên hơn lỗi tải chương: lúc đang tự chuyển chương bằng TTS, lỗi dịch cũng đặt `chapterError` để bộ đọc
 * dừng hẳn, nhưng người đọc phải thấy thẻ dịch với nút [Đọc bản gốc].
 */
@Composable
internal fun ReaderBody(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val chapter = state.currentChapter
    val hasText = state.paragraphs.isNotEmpty()
    val translatingCurrent = state.isTranslating &&
        (state.translatingChapterNumber == null || state.translatingChapterNumber == chapter?.chapterNumber)

    when {
        !hasText && state.isLoadingChapter ->
            ReaderChapterSpinner(target = state.targetChapterNumber ?: chapter?.chapterNumber, modifier = modifier)

        !hasText && translatingCurrent -> TranslationProgressCard(
            statusText = "AI đang dịch chương ${state.translatingChapterNumber ?: chapter?.chapterNumber ?: ""}…",
            onCancel = viewModel::cancelTranslation,
            onReadOriginal = if (chapter?.originalContent.isNullOrBlank()) null else viewModel::readOriginal,
            modifier = modifier
        )

        !hasText && state.translationError != null -> ReaderTranslationFailedView(
            message = state.translationError,
            kind = state.translationErrorKind,
            canReadOriginal = !chapter?.originalContent.isNullOrBlank(),
            onRetry = viewModel::retryLoadChapter,
            onReadOriginal = viewModel::readOriginal,
            onOpenToc = viewModel::toggleQuickToc,
            modifier = modifier
        )

        !hasText && state.chapterError != null -> ReaderErrorView(
            errorMessage = state.chapterError,
            onOpenToc = viewModel::toggleQuickToc,
            onRetry = viewModel::retryLoadChapter,
            modifier = modifier
        )

        chapter != null -> ReaderChapterContent(state, viewModel, modifier)
    }
}

@Composable
private fun ReaderChapterContent(state: ReaderUiState, viewModel: ReaderViewModel, modifier: Modifier) {
    val chapter = state.currentChapter ?: return
    val chapterKey = state.chapterKey ?: return
    ReaderContent(
        chapterKey = chapterKey,
        title = chapter.titleVi.ifBlank { chapter.title },
        paragraphs = state.paragraphs,
        fontSize = state.fontSize,
        fontFamilyType = state.fontFamilyType,
        lineSpacingFactor = state.lineSpacingFactor,
        isTtsActive = state.isTtsActive,
        activeParagraphIndex = state.ttsCurrentParagraph,
        ttsAutoScroll = state.ttsAutoScroll,
        isPaginatedMode = state.isPaginatedMode,
        pageMargin = state.pageMargin,
        isEnglishMode = state.isEnglishMode,
        isBilingualMode = state.isBilingualMode,
        isProgressiveMode = state.isProgressiveMode,
        progressiveParagraphs = state.progressiveParagraphs,
        englishParagraphs = state.englishParagraphs,
        bilingualViParagraphs = state.bilingualViParagraphs,
        isAdaptingEnglish = state.isAdaptingEnglish,
        englishNotice = state.englishNotice,
        onRetryEnglishAdaptation = viewModel::adaptChapterToEnglish,
        englishLevel = state.englishLevel,
        bookmarks = state.chapterBookmarks,
        currentChapterNumber = chapter.chapterNumber,
        isAutoScrolling = state.isAutoScrolling,
        isAutoScrollPaused = state.isAutoScrollPaused,
        autoScrollSpeed = state.autoScrollSpeed,
        endOfBook = state.endOfBookUi(),
        onAutoScrollReachedEnd = viewModel::stopAutoScroll,
        onNextChapter = viewModel::nextChapter,
        onPrevChapter = viewModel::prevChapter,
        onBookmarkParagraph = { pIdx, text, existing -> viewModel.openAddBookmarkDialog(pIdx, text, existing) },
        onSelectWord = viewModel::onSelectEnglishWord,
        onAnalyzeSentence = viewModel::analyzeSentence,
        onParagraphClick = viewModel::jumpTtsParagraph,
        onOpenRecap = viewModel::openChapterRecap,
        onQuoteParagraph = viewModel::openQuoteCard,
        onToggleEnglishMode = viewModel::toggleEnglishMode,
        onToggleBilingualMode = viewModel::toggleBilingualMode,
        onCheckNewChapters = viewModel::checkForNewChapters,
        onEnableAlerts = viewModel::enableNewChapterAlerts,
        onEndReached = { viewModel.onEndReached(chapterKey) },
        onScrollPositionChanged = { position -> viewModel.onScrollPositionChanged(chapterKey, position) },
        restoreScrollPosition = state.restoreScrollPosition,
        onScrollRestored = viewModel::onScrollRestored,
        scrollToParagraphIndex = state.scrollToParagraphIndex,
        onParagraphScrolled = viewModel::onParagraphScrolled,
        onTap = viewModel::toggleBars,
        modifier = modifier
    )
}
