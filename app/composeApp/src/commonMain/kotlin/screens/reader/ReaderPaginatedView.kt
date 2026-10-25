package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.theme.*
import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.reader.PageSpan
import com.truyencloud.shared.reader.ReaderKeyAction
import com.truyencloud.shared.reader.ReaderPageAnchor
import com.truyencloud.shared.reader.ReaderScrollRestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@Composable
internal fun ReaderPaginatedView(
    /** Khoá chương: trạng thái trang và vị trí ban đầu gắn với đúng chương này (không thừa hưởng trang của chương trước). */
    chapterKey: String,
    title: String,
    effectiveParagraphs: List<String>,
    /** Bản đối chiếu (tiếng Việt) ghép theo chỉ số với đoạn hiển thị ở chế độ song ngữ. */
    paragraphs: List<String>,
    fontSize: Int,
    fontFamilyType: String,
    contentFont: FontFamily,
    lineSpacingFactor: Float,
    pageMargin: Int,
    isBilingualMode: Boolean,
    isEnglishMode: Boolean,
    isProgressiveMode: Boolean,
    isAdaptingEnglish: Boolean = false,
    englishNotice: String? = null,
    onRetryEnglishAdaptation: () -> Unit = {},
    englishLevel: String,
    bookmarks: List<Bookmark>,
    currentChapterNumber: Int,
    isTtsActive: Boolean,
    activeParagraphIndex: Int,
    ttsAutoScroll: Boolean,
    restoreScrollPosition: Float,
    onScrollPositionChanged: (Float) -> Unit,
    onScrollRestored: () -> Unit,
    scrollToParagraphIndex: Int? = null,
    onParagraphScrolled: () -> Unit = {},
    onEndReached: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onBookmarkParagraph: (paragraphIndex: Int, text: String, existingBookmark: Bookmark?) -> Unit,
    onQuoteParagraph: (String) -> Unit,
    onSelectWord: (word: String, sentence: String, charOffset: Int) -> Unit,
    onAnalyzeSentence: (String) -> Unit,
    onParagraphClick: (Int) -> Unit,
    onOpenRecap: () -> Unit,
    onToggleEnglishMode: () -> Unit,
    onToggleBilingualMode: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)

    val bodyStyle = remember(contentFont, fontSize, lineSpacingFactor) {
        TextStyle(
            fontFamily = contentFont,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * lineSpacingFactor).sp,
            letterSpacing = 0.3.sp
        )
    }
    val subStyle = remember(fontSize) {
        val subFontSize = (fontSize - 3).coerceAtLeast(12)
        TextStyle(
            fontFamily = UIFont,
            fontSize = subFontSize.sp,
            lineHeight = (subFontSize * 1.5f).sp,
            letterSpacing = 0.1.sp
        )
    }
    val titleStyle = remember(contentFont, fontSize) {
        TextStyle(
            fontFamily = contentFont,
            fontSize = (fontSize + 5).sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = (fontSize + 11).sp,
            letterSpacing = 0.2.sp
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = pageMargin.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        if (isAdaptingEnglish) {
            ReaderBannerCard(
                message = "AI đang chuyển ngữ sang tiếng Anh ${englishLevel.uppercase()}…",
                tone = NoticeTone.Info,
                busy = true,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        } else if (englishNotice != null && (isEnglishMode || isBilingualMode)) {
            ReaderBannerCard(
                message = englishNotice,
                tone = NoticeTone.Warning,
                actions = listOf(BannerAction("Thử lại", primary = true, onClick = onRetryEnglishAdaptation)),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val availableWidthPx = constraints.maxWidth
            val footerHeightPx = with(density) { ReaderPaginatedFooterHeight.roundToPx() }
            val ttsSpacerPx = if (isTtsActive) with(density) { 58.dp.roundToPx() } else 0
            val availableHeightPx = (constraints.maxHeight - footerHeightPx - ttsSpacerPx).coerceAtLeast(100)
            val hasBilingualSub = isBilingualMode && paragraphs.isNotEmpty() && effectiveParagraphs !== paragraphs

            val pages = remember(
                effectiveParagraphs, paragraphs, hasBilingualSub, title, availableWidthPx, availableHeightPx,
                fontSize, fontFamilyType, lineSpacingFactor, pageMargin
            ) {
                ReaderPagingEngine.paginateChapter(
                    paragraphs = effectiveParagraphs,
                    title = title,
                    availableWidthPx = availableWidthPx,
                    availableHeightPx = availableHeightPx,
                    textMeasurer = textMeasurer,
                    bodyStyle = bodyStyle,
                    titleStyle = titleStyle,
                    density = density,
                    paragraphSpacingDp = 12.dp,
                    subStyle = if (hasBilingualSub) subStyle else null,
                    secondaryParagraphs = if (hasBilingualSub) paragraphs else null
                )
            }

            // Trạng thái trang riêng cho từng chương: sang chương mới luôn bắt đầu ở trang 0 rồi mới khôi phục vị trí đã lưu
            val pagerState = key(chapterKey) { rememberPagerState(initialPage = 0, pageCount = { pages.size }) }
            val pagerScope = rememberCoroutineScope()
            val hold = remember { PagerManualTurnHold() }
            // Chỗ đang đọc (chỉ số đoạn văn) neo qua các lần chia trang lại — xem ReaderPageAnchor
            val anchor = remember(chapterKey) { ReaderPageAnchor() }
            // Chương đã đặt xong vị trí ban đầu: trước đó không được báo tiến độ (sẽ lưu nhầm trang của chương cũ)
            var settledKey by remember { mutableStateOf<String?>(null) }
            val reportProgress by rememberUpdatedState(onScrollPositionChanged)
            val reportEnd by rememberUpdatedState(onEndReached)

            // Tự neo lại trang khi chia trang lại (đổi cỡ chữ, lề, giãn dòng, xoay màn hình) — chỉ sau khi chương đã đặt xong vị trí ban đầu.
            // Quyết định NGAY LÚC DỰNG chứ không trong hiệu ứng: lúc này pager chưa đo lại nên currentPage còn là chỉ số của cách chia CŨ.
            // (Bản cũ ghi lại "đoạn đang đọc" trong một hiệu ứng khoá theo cả `pages`; nó chạy trước hiệu ứng neo lại và ghi đè chỗ đọc
            // bằng đoạn đầu của trang CÙNG SỐ trong cách chia mới, nên không cuộn gì và người đọc bị kéo lùi.)
            val pageSpans = remember(pages) {
                pages.map { page -> PageSpan(page.chunks.firstOrNull()?.paragraphIndex ?: -1, page.chunks.lastOrNull()?.paragraphIndex ?: -1) }
            }
            val reanchorPage = anchor.observe(
                spans = pageSpans,
                currentPage = pagerState.currentPage,
                canReanchor = restoreScrollPosition <= 0f && settledKey == chapterKey
            )
            LaunchedEffect(pages) {
                val target = reanchorPage ?: return@LaunchedEffect
                try {
                    pagerState.scrollToPage(target)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
                anchor.landed(pageSpans, pagerState.currentPage)
            }

            // Nhảy chính xác tới trang chứa đoạn đích (dấu trang / tìm trong chương)
            LaunchedEffect(scrollToParagraphIndex, pages.size) {
                val targetIdx = scrollToParagraphIndex ?: return@LaunchedEffect
                if (pages.isEmpty()) return@LaunchedEffect
                val targetPage = pages.indexOfFirst { p -> p.chunks.any { it.paragraphIndex == targetIdx } }
                if (targetPage >= 0) {
                    try {
                        pagerState.scrollToPage(targetPage)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                    anchor.landed(pageSpans, pagerState.currentPage)
                }
                settledKey = chapterKey
                onParagraphScrolled()
            }

            // Vị trí ban đầu của chương: trang đã lưu, hoặc trang đầu khi chưa đọc (0) — tính một lần cho mỗi chương
            LaunchedEffect(chapterKey, restoreScrollPosition, pages.size) {
                if (pages.isEmpty() || scrollToParagraphIndex != null) return@LaunchedEffect
                val decision = ReaderScrollRestore.decide(restoreScrollPosition, pages.size, alreadyRestored = settledKey == chapterKey)
                if (decision is ReaderScrollRestore.Decision.ScrollTo) {
                    try {
                        pagerState.scrollToPage(decision.index.coerceIn(0, pages.size - 1))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }
                settledKey = chapterKey
                if (restoreScrollPosition > 0f) onScrollRestored()
            }

            LaunchedEffect(pagerState, chapterKey, pages.size) {
                snapshotFlow {
                    if (pages.isEmpty() || !ReaderScrollRestore.shouldReport(settledKey, chapterKey)) null
                    else ReaderScrollRestore.positionFor(pagerState.currentPage, pages.size)
                }.filterNotNull().distinctUntilChanged().collect { reportProgress(it) }
            }

            LaunchedEffect(pagerState.currentPage, pages.size, settledKey) {
                if (settledKey == chapterKey && pages.isNotEmpty() && pagerState.currentPage >= pages.size - 1) reportEnd()
            }

            TtsPagerFollowEffect(pagerState, pages, ttsAutoScroll, isTtsActive, activeParagraphIndex, hold)

            // Một lần lật thủ công: -1 trang trước, +1 trang sau; hết chương thì sang chương kế / trước
            fun turnPage(delta: Int) {
                hold.markManualTurn()
                val target = pagerState.currentPage + delta
                when {
                    target in pages.indices -> pagerScope.launch { pagerState.animateScrollToPage(target) }
                    delta < 0 -> if (currentChapterNumber > 1) onPrevChapter()
                    else -> onNextChapter()
                }
            }

            VolumeKeyPageEffect(enabled = true) { action ->
                when (action) {
                    ReaderKeyAction.NEXT_PAGE -> turnPage(+1)
                    ReaderKeyAction.PREVIOUS_PAGE -> turnPage(-1)
                    else -> Unit
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    key = { index -> "$chapterKey:$index" }
                ) { pageIndex ->
                    val page = pages.getOrNull(pageIndex)
                    if (page != null) {
                        ReaderPagedPage(
                            page = page,
                            pageIndex = pageIndex,
                            title = title,
                            fontSize = fontSize,
                            contentFont = contentFont,
                            bodyStyle = bodyStyle,
                            isBilingualMode = isBilingualMode,
                            isEnglishMode = isEnglishMode,
                            isProgressiveMode = isProgressiveMode,
                            englishLevel = englishLevel,
                            bookmarks = bookmarks,
                            isTtsActive = isTtsActive,
                            activeParagraphIndex = activeParagraphIndex,
                            onZoneTap = { xRatio ->
                                when {
                                    xRatio < 0.25f -> turnPage(-1)
                                    xRatio > 0.75f -> turnPage(+1)
                                    else -> onTap()
                                }
                            },
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
                    }
                }

                // Page navigation footer with interactive prev/next
                ReaderPaginatedFooter(
                    currentPage = pagerState.currentPage,
                    pageCount = pages.size,
                    currentChapterNumber = currentChapterNumber,
                    onPrevPage = { turnPage(-1) },
                    onNextPage = { turnPage(+1) },
                    onPrevChapter = onPrevChapter,
                    onNextChapter = onNextChapter
                )

                if (isTtsActive) {
                    Spacer(modifier = Modifier.height(58.dp))
                }
            }
        }
    }
}
