package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.truyencloud.shared.data.Bookmark

/**
 * Một trang sách trong chế độ lật trang: tiêu đề chương (trang đầu) + các đoạn. Chạm 25% mép trái/phải để lật trang,
 * chạm giữa để hiện/ẩn thanh công cụ — cả khi chạm vào nền trang lẫn vào một đoạn ([onZoneTap] nhận tỉ lệ ngang 0..1).
 */
@Composable
internal fun ReaderPagedPage(
    page: BookPage,
    pageIndex: Int,
    title: String,
    fontSize: Int,
    contentFont: FontFamily,
    bodyStyle: TextStyle,
    isBilingualMode: Boolean,
    isEnglishMode: Boolean,
    isProgressiveMode: Boolean,
    englishLevel: String,
    bookmarks: List<Bookmark>,
    isTtsActive: Boolean,
    activeParagraphIndex: Int,
    onZoneTap: (xRatio: Float) -> Unit,
    onBookmarkParagraph: (paragraphIndex: Int, text: String, existingBookmark: Bookmark?) -> Unit,
    onQuoteParagraph: (String) -> Unit,
    onSelectWord: (word: String, sentence: String, charOffset: Int) -> Unit,
    onAnalyzeSentence: (String) -> Unit,
    onParagraphClick: (Int) -> Unit,
    onOpenRecap: () -> Unit,
    onToggleEnglishMode: () -> Unit,
    onToggleBilingualMode: () -> Unit,
    onTap: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(pageIndex) {
                detectTapGestures(onTap = { offset -> onZoneTap(offset.x / size.width) })
            },
        verticalArrangement = Arrangement.Top
    ) {
        if (page.hasChapterHeader) {
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
                modifier = Modifier.padding(bottom = 14.dp),
                isProgressiveMode = isProgressiveMode
            )
        }

        page.chunks.forEach { chunk ->
            PagedParagraphItem(
                chunk = chunk,
                bodyStyle = bodyStyle,
                fontSize = fontSize,
                isSpeaking = isTtsActive && chunk.paragraphIndex == activeParagraphIndex,
                isTtsActive = isTtsActive,
                isEnglishMode = isEnglishMode,
                isBilingualMode = isBilingualMode,
                isProgressiveMode = isProgressiveMode,
                bookmark = bookmarks.find { it.paragraphIndex == chunk.paragraphIndex },
                onBookmarkParagraph = onBookmarkParagraph,
                onQuoteParagraph = onQuoteParagraph,
                onSelectWord = onSelectWord,
                onAnalyzeSentence = onAnalyzeSentence,
                onParagraphClick = onParagraphClick,
                onTap = onTap,
                onPageTap = onZoneTap
            )
            if (chunk.isEndOfParagraph) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
