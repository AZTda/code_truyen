package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.*
import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.text.ParagraphSplitter

internal fun isEnglishWord(word: String): Boolean {
    if (word.length < 2) return false
    return word.all { (it in 'a'..'z') || (it in 'A'..'Z') || it == '\'' || it == '-' }
}

@Composable
internal fun ReaderParagraphItem(
    idx: Int,
    primaryText: String,
    paragraphs: List<String>,
    englishParagraphs: List<String>,
    fontSize: Int,
    contentFont: androidx.compose.ui.text.font.FontFamily,
    lineSpacingFactor: Float,
    isSpeaking: Boolean,
    isTtsActive: Boolean = false,
    isEnglishMode: Boolean,
    isBilingualMode: Boolean,
    isProgressiveMode: Boolean = false,
    bookmark: Bookmark? = null,
    onBookmarkParagraph: (paragraphIndex: Int, text: String, existingBookmark: Bookmark?) -> Unit = { _, _, _ -> },
    onQuoteParagraph: (String) -> Unit,
    onSelectWord: (word: String, sentence: String, charOffset: Int) -> Unit,
    onAnalyzeSentence: (String) -> Unit,
    onParagraphClick: (Int) -> Unit,
    onTap: () -> Unit
) {
    val colors = LocalCustomColors.current
    val bmColor = bookmark?.let { parseHexColor(it.colorHex) }
    val viSubText = if (isBilingualMode && englishParagraphs.isNotEmpty()) {
        ParagraphSplitter.pairBilingual(
            primaryIndex = idx,
            primaryCount = englishParagraphs.size,
            secondary = paragraphs
        )
    } else null
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(AppShapes.small)
            .then(
                if (isSpeaking) {
                    Modifier
                        .background(colors.accent.copy(alpha = 0.07f))
                        .border(
                            width = 0.6.dp,
                            color = colors.accent.copy(alpha = 0.35f),
                            shape = AppShapes.small
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                } else if (bookmark != null && bmColor != null) {
                    Modifier
                        .background(bmColor.copy(alpha = 0.12f))
                        .border(
                            width = 1.dp,
                            color = bmColor.copy(alpha = 0.5f),
                            shape = AppShapes.small
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                } else {
                    Modifier
                }
            )
            .pointerInput(primaryText, isSpeaking, isTtsActive, bookmark, isEnglishMode, isBilingualMode, isProgressiveMode, idx) {
                detectTapGestures(
                    onLongPress = { offset ->
                        val layout = layoutResult
                        if ((isEnglishMode || isBilingualMode || isProgressiveMode) && layout != null) {
                            val charOffset = layout.getOffsetForPosition(offset)
                            val sentence = extractSentenceAt(primaryText, charOffset)
                            if (sentence.length >= 8) {
                                onAnalyzeSentence(sentence)
                                return@detectTapGestures
                            }
                        }
                        onBookmarkParagraph(idx, primaryText, bookmark)
                    },
                    onTap = { offset ->
                        val layout = layoutResult
                        if (layout != null) {
                            val charOffset = layout.getOffsetForPosition(offset)
                            val (word, sentence) = extractWordAndSentence(primaryText, charOffset)
                            if ((isEnglishMode || isBilingualMode || isProgressiveMode) && word.isNotBlank() && isEnglishWord(word)) {
                                onSelectWord(word, sentence, charOffset)
                                return@detectTapGestures
                            }
                        }
                        if (isTtsActive && !isSpeaking) onParagraphClick(idx) else onTap()
                    }
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = primaryText,
                color = if (isSpeaking) colors.accent else colors.textPrimary,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * lineSpacingFactor).sp,
                fontFamily = contentFont,
                letterSpacing = 0.2.sp,
                onTextLayout = { layoutResult = it },
                modifier = Modifier.fillMaxWidth()
            )

            if (viSubText != null && (isBilingualMode || isEnglishMode)) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = viSubText,
                    color = colors.textSecondary.copy(alpha = 0.82f),
                    fontSize = (fontSize - 3).coerceAtLeast(12).sp,
                    lineHeight = ((fontSize - 3).coerceAtLeast(12) * 1.5f).sp,
                    fontFamily = UIFont,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (bookmark != null && bmColor != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .clip(AppShapes.small)
                        .background(bmColor.copy(alpha = 0.22f))
                        .clickable { onBookmarkParagraph(idx, primaryText, bookmark) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = TruyenCloudIcons.BookmarkFilled,
                        contentDescription = "Ghi chú dấu trang",
                        tint = bmColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (bookmark.note.isNotBlank()) bookmark.note else "Dấu trang (chạm để sửa)",
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = UIFont
                    )
                }
            }
        }
    }
}

internal fun extractWordAndSentence(text: String, charOffset: Int): Pair<String, String> {
    if (text.isBlank() || charOffset !in 0 until text.length) return Pair("", "")

    val ch = text[charOffset]
    if (!ch.isLetter() && ch != '\'' && ch != '-') {
        return Pair("", "")
    }

    var start = charOffset
    while (start > 0 && (text[start - 1].isLetter() || text[start - 1] == '\'' || text[start - 1] == '-')) {
        start--
    }

    var end = charOffset
    while (end < text.length && (text[end].isLetter() || text[end] == '\'' || text[end] == '-')) {
        end++
    }

    val rawWord = text.substring(start, end).trim('\'', '-')

    // Extract sentence boundaries
    val sentencePunctuation = setOf('.', '!', '?', ';', '\n', '。', '！', '？')
    var sentStart = start
    while (sentStart > 0 && text[sentStart - 1] !in sentencePunctuation) {
        sentStart--
    }
    var sentEnd = end
    while (sentEnd < text.length && text[sentEnd] !in sentencePunctuation) {
        sentEnd++
    }
    val sentence = text.substring(sentStart, sentEnd.coerceAtMost(text.length)).trim()

    return Pair(rawWord, sentence)
}

/** Tach dung cau chua vi tri ky tu duoc cham vao. */
internal fun extractSentenceAt(text: String, charOffset: Int): String {
    if (text.isBlank()) return ""
    val pos = charOffset.coerceIn(0, (text.length - 1).coerceAtLeast(0))
    val punctuation = setOf('.', '!', '?', ';', '\n', '。', '！', '？')

    var start = pos
    while (start > 0 && text[start - 1] !in punctuation) start--
    var end = pos
    while (end < text.length && text[end] !in punctuation) end++
    if (end < text.length) end++  // giu lai dau cau ket thuc

    return text.substring(start, end.coerceAtMost(text.length)).trim()
}

