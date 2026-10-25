package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.Bookmark

@Composable
internal fun ReaderContinuousScrollView(
    listState: LazyListState,
    /** Khoá chương: gắn vào khoá từng mục để danh sách không tái dùng mục của chương trước (mở nhầm độ sâu cuộn). */
    chapterKey: String,
    title: String,
    effectiveParagraphs: List<String>,
    /** Bản đối chiếu (tiếng Việt) ghép theo chỉ số với đoạn hiển thị ở chế độ song ngữ. */
    paragraphs: List<String>,
    englishParagraphs: List<String>,
    fontSize: Int,
    contentFont: FontFamily,
    lineSpacingFactor: Float,
    pageMargin: Int,
    isBilingualMode: Boolean,
    isEnglishMode: Boolean,
    isProgressiveMode: Boolean,
    isAdaptingEnglish: Boolean,
    englishLevel: String,
    bookmarks: List<Bookmark>,
    currentChapterNumber: Int,
    isTtsActive: Boolean,
    activeParagraphIndex: Int,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
    onBookmarkParagraph: (paragraphIndex: Int, text: String, existingBookmark: Bookmark?) -> Unit,
    onQuoteParagraph: (String) -> Unit,
    onSelectWord: (word: String, sentence: String, charOffset: Int) -> Unit,
    onAnalyzeSentence: (String) -> Unit,
    onParagraphClick: (Int) -> Unit,
    onOpenRecap: () -> Unit,
    onToggleEnglishMode: () -> Unit,
    onToggleBilingualMode: () -> Unit,
    onTap: () -> Unit,
    endOfBook: EndOfBookUi = EndOfBookUi(),
    onCheckNewChapters: () -> Unit = {},
    onEnableAlerts: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = pageMargin.dp)
    ) {
        item(key = "$chapterKey:header") {
            // Chừa chỗ cho thanh trên (vùng thanh trạng thái + 52dp) để tên chương không bị thanh đặc che khi thanh đang hiện
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(Spacing.topBarHeight + 24.dp))

            // Literary Chapter Header
            ReaderChapterHeader(
                title = title,
                fontSize = fontSize,
                contentFont = contentFont,
                isBilingualMode = isBilingualMode,
                isEnglishMode = isEnglishMode,
                englishLevel = englishLevel,
                onOpenRecap = onOpenRecap,
                onToggleEnglishMode = onToggleEnglishMode,
                onToggleBilingualMode = onToggleBilingualMode,
                isProgressiveMode = isProgressiveMode
            )

            // Banner tiến trình chuyển thể chương sang tiếng Anh
            if (isAdaptingEnglish) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                ReaderBannerCard(
                    message = "AI đang chuyển thể chương sang tiếng Anh ${englishLevel.uppercase()}…",
                    tone = NoticeTone.Info,
                    busy = true
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))
        }

        itemsIndexed(effectiveParagraphs, key = { idx, _ -> "$chapterKey:$idx" }) { idx, primaryText ->
            val bookmark = bookmarks.find { it.paragraphIndex == idx }
            ReaderParagraphItem(
                idx = idx,
                primaryText = primaryText,
                paragraphs = paragraphs,
                englishParagraphs = englishParagraphs,
                fontSize = fontSize,
                contentFont = contentFont,
                lineSpacingFactor = lineSpacingFactor,
                isSpeaking = isTtsActive && idx == activeParagraphIndex,
                isTtsActive = isTtsActive,
                isEnglishMode = isEnglishMode,
                isBilingualMode = isBilingualMode,
                isProgressiveMode = isProgressiveMode,
                bookmark = bookmark,
                onBookmarkParagraph = onBookmarkParagraph,
                onQuoteParagraph = onQuoteParagraph,
                onSelectWord = onSelectWord,
                onAnalyzeSentence = onAnalyzeSentence,
                onParagraphClick = onParagraphClick,
                onTap = onTap
            )
        }

        item(key = "$chapterKey:end") {
            Spacer(modifier = Modifier.height(Spacing.xl))
            ReaderEndOfChapterCard(
                currentChapterNumber = currentChapterNumber,
                title = title,
                contentFont = contentFont,
                onNextChapter = onNextChapter,
                onPrevChapter = onPrevChapter,
                onOpenRecap = onOpenRecap,
                endOfBook = endOfBook,
                onCheckNewChapters = onCheckNewChapters,
                onEnableAlerts = onEnableAlerts
            )
            Spacer(modifier = Modifier.height(Spacing.bottomBarHeight + 90.dp))
        }
    }
}
