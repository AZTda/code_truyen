package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.compose.theme.UIFont
import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.reader.ReaderScrollRestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
internal fun ReaderContent(
    /** Khoá chương (truyện + số chương): gắn trạng thái cuộn, khôi phục vị trí và khoá mục danh sách với đúng chương này. */
    chapterKey: String,
    title: String,
    paragraphs: List<String>,
    fontSize: Int,
    fontFamilyType: String = "serif",
    lineSpacingFactor: Float = 1.8f,
    isTtsActive: Boolean = false,
    activeParagraphIndex: Int = 0,
    ttsAutoScroll: Boolean = true,
    isPaginatedMode: Boolean = false,
    pageMargin: Int = 16,
    isEnglishMode: Boolean = false,
    isBilingualMode: Boolean = false,
    isProgressiveMode: Boolean = false,
    progressiveParagraphs: List<String> = emptyList(),
    englishParagraphs: List<String> = emptyList(),
    /** Phần tiếng Việt căn hàng của các cặp [EN]/[VI] (song ngữ); rỗng thì đối chiếu với [paragraphs]. */
    bilingualViParagraphs: List<String> = emptyList(),
    isAdaptingEnglish: Boolean = false,
    englishNotice: String? = null,
    onRetryEnglishAdaptation: () -> Unit = {},
    englishLevel: String = "c1",
    bookmarks: List<Bookmark> = emptyList(),
    currentChapterNumber: Int = 1,
    isAutoScrolling: Boolean = false,
    isAutoScrollPaused: Boolean = false,
    autoScrollSpeed: Float = 1.0f,
    endOfBook: EndOfBookUi = EndOfBookUi(),
    onAutoScrollReachedEnd: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    onPrevChapter: () -> Unit = {},
    onBookmarkParagraph: (paragraphIndex: Int, text: String, existingBookmark: Bookmark?) -> Unit = { _, _, _ -> },
    onSelectWord: (word: String, sentence: String, charOffset: Int) -> Unit = { _, _, _ -> },
    onAnalyzeSentence: (String) -> Unit = {},
    onParagraphClick: (Int) -> Unit = {},
    onOpenRecap: () -> Unit = {},
    onQuoteParagraph: (String) -> Unit = {},
    onToggleEnglishMode: () -> Unit = {},
    onToggleBilingualMode: () -> Unit = {},
    onCheckNewChapters: () -> Unit = {},
    onEnableAlerts: () -> Unit = {},
    /** Người đọc đã tới thẻ hết chương / trang cuối (mới tính là đọc xong chương). */
    onEndReached: () -> Unit = {},
    onScrollPositionChanged: (Float) -> Unit = {},
    /** Vi tri cuon can khoi phuc (0f = khong co vi tri luu: chuong chua doc mo o dau chuong). */
    restoreScrollPosition: Float = 0f,
    onScrollRestored: () -> Unit = {},
    scrollToParagraphIndex: Int? = null,
    onParagraphScrolled: () -> Unit = {},
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Mỗi chương một trạng thái cuộn riêng: đổi chương luôn bắt đầu ở mục 0 rồi mới khôi phục vị trí đã lưu, thay vì
    // giữ chỉ số mục của chương trước (chương ngắn hơn thì rơi thẳng xuống thẻ hết chương).
    val listState = key(chapterKey) { rememberLazyListState() }
    val interactionSource = remember { MutableInteractionSource() }
    val contentFont = if (fontFamilyType == "serif") ReaderFont else UIFont

    // Effective paragraph list to render
    val effectiveParagraphs = when {
        isProgressiveMode && progressiveParagraphs.isNotEmpty() -> progressiveParagraphs
        (isBilingualMode || isEnglishMode) && englishParagraphs.isNotEmpty() -> englishParagraphs
        else -> paragraphs
    }
    val secondaryParagraphs = when {
        !isBilingualMode || englishParagraphs.isEmpty() -> emptyList()
        bilingualViParagraphs.isNotEmpty() -> bilingualViParagraphs
        else -> paragraphs
    }

    // Tu cuon theo doan TTS dang doc (che do cuon): ton trong cai dat ttsAutoScroll, tam ngung ~5s sau khi nguoi dung tu cuon
    TtsAutoFollowEffect(
        listState = listState,
        enabled = ttsAutoScroll && !isPaginatedMode,
        isTtsActive = isTtsActive,
        activeParagraphIndex = activeParagraphIndex,
        paragraphCount = effectiveParagraphs.size
    )
    ReaderAutoScrollEffect(listState, !isPaginatedMode && isAutoScrolling, isAutoScrollPaused, autoScrollSpeed, onAutoScrollReachedEnd)
    VolumeKeyScrollEffect(listState, enabled = !isPaginatedMode)

    // Chương đã đặt xong vị trí ban đầu; trước đó KHÔNG được báo tiến độ cuộn về ViewModel: lần phát đầu tiên sau khi đổi
    // chương mang độ sâu của chương trước và sẽ bị lưu nhầm làm tiến độ của chương mới.
    var settledKey by remember { mutableStateOf<String?>(null) }
    val reportProgress by rememberUpdatedState(onScrollPositionChanged)
    val reportEnd by rememberUpdatedState(onEndReached)

    LaunchedEffect(chapterKey, restoreScrollPosition, effectiveParagraphs.size, isPaginatedMode) {
        if (isPaginatedMode || effectiveParagraphs.isEmpty()) return@LaunchedEffect
        val decision = ReaderScrollRestore.decide(
            savedPosition = restoreScrollPosition,
            itemCount = ReaderScrollRestore.listItemCount(effectiveParagraphs.size),
            alreadyRestored = settledKey == chapterKey
        )
        if (decision is ReaderScrollRestore.Decision.ScrollTo) {
            try {
                listState.scrollToItem(decision.index)
                settledKey = chapterKey
                if (restoreScrollPosition > 0f) onScrollRestored()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Danh sách chưa kịp bố cục: lần đổi kích thước kế tiếp sẽ thử lại
            }
        } else {
            settledKey = chapterKey
            if (restoreScrollPosition > 0f) onScrollRestored()
        }
    }

    LaunchedEffect(scrollToParagraphIndex, isPaginatedMode, effectiveParagraphs.size) {
        if (!isPaginatedMode && scrollToParagraphIndex != null && effectiveParagraphs.isNotEmpty()) {
            val targetIdx = (scrollToParagraphIndex + 1).coerceIn(0, effectiveParagraphs.size)
            try { listState.animateScrollToItem(targetIdx) } catch (_: Exception) {}
            onParagraphScrolled()
        }
    }

    // Theo dõi vị trí cuộn để lưu tiến độ (ViewModel tự giãn nhịp)
    LaunchedEffect(listState, chapterKey, isPaginatedMode) {
        if (isPaginatedMode) return@LaunchedEffect
        snapshotFlow {
            if (!ReaderScrollRestore.shouldReport(settledKey, chapterKey)) null
            else ReaderScrollRestore.positionFor(listState.firstVisibleItemIndex, listState.layoutInfo.totalItemsCount)
        }.filterNotNull().distinctUntilChanged().collect { reportProgress(it) }
    }

    // Thẻ hết chương (mục cuối) đã hiện ra: người đọc đã đọc tới cuối chương
    LaunchedEffect(listState, chapterKey, isPaginatedMode) {
        if (isPaginatedMode) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            settledKey == chapterKey && info.totalItemsCount > 0 &&
                info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
        }.first { it }
        reportEnd()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap
            )
    ) {
        if (isPaginatedMode) {
            ReaderPaginatedView(
                chapterKey = chapterKey,
                title = title,
                effectiveParagraphs = effectiveParagraphs,
                paragraphs = secondaryParagraphs,
                fontSize = fontSize,
                fontFamilyType = fontFamilyType,
                contentFont = contentFont,
                lineSpacingFactor = lineSpacingFactor,
                pageMargin = pageMargin,
                isBilingualMode = isBilingualMode,
                isEnglishMode = isEnglishMode,
                isProgressiveMode = isProgressiveMode,
                isAdaptingEnglish = isAdaptingEnglish,
                englishNotice = englishNotice,
                onRetryEnglishAdaptation = onRetryEnglishAdaptation,
                englishLevel = englishLevel,
                bookmarks = bookmarks,
                currentChapterNumber = currentChapterNumber,
                isTtsActive = isTtsActive,
                activeParagraphIndex = activeParagraphIndex,
                ttsAutoScroll = ttsAutoScroll,
                restoreScrollPosition = restoreScrollPosition,
                onScrollPositionChanged = onScrollPositionChanged,
                onScrollRestored = onScrollRestored,
                scrollToParagraphIndex = scrollToParagraphIndex,
                onParagraphScrolled = onParagraphScrolled,
                onEndReached = onEndReached,
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter,
                onBookmarkParagraph = onBookmarkParagraph,
                onQuoteParagraph = onQuoteParagraph,
                onSelectWord = onSelectWord,
                onAnalyzeSentence = onAnalyzeSentence,
                onParagraphClick = onParagraphClick,
                onOpenRecap = onOpenRecap,
                onToggleEnglishMode = onToggleEnglishMode,
                onToggleBilingualMode = onToggleBilingualMode,
                onTap = onTap
            )
        } else {
            ReaderContinuousScrollView(
                listState = listState,
                chapterKey = chapterKey,
                title = title,
                effectiveParagraphs = effectiveParagraphs,
                paragraphs = secondaryParagraphs,
                englishParagraphs = englishParagraphs,
                fontSize = fontSize,
                contentFont = contentFont,
                lineSpacingFactor = lineSpacingFactor,
                pageMargin = pageMargin,
                isBilingualMode = isBilingualMode,
                isEnglishMode = isEnglishMode,
                isProgressiveMode = isProgressiveMode,
                isAdaptingEnglish = isAdaptingEnglish,
                englishLevel = englishLevel,
                bookmarks = bookmarks,
                currentChapterNumber = currentChapterNumber,
                isTtsActive = isTtsActive,
                activeParagraphIndex = activeParagraphIndex,
                onNextChapter = onNextChapter,
                onPrevChapter = onPrevChapter,
                onBookmarkParagraph = onBookmarkParagraph,
                onQuoteParagraph = onQuoteParagraph,
                onSelectWord = onSelectWord,
                onAnalyzeSentence = onAnalyzeSentence,
                onParagraphClick = onParagraphClick,
                onOpenRecap = onOpenRecap,
                onToggleEnglishMode = onToggleEnglishMode,
                onToggleBilingualMode = onToggleBilingualMode,
                onTap = onTap,
                endOfBook = endOfBook,
                onCheckNewChapters = onCheckNewChapters,
                onEnableAlerts = onEnableAlerts
            )
        }
    }
}
