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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.*
import com.truyencloud.shared.data.Bookmark

@Composable
internal fun PagedParagraphItem(
    chunk: PagedChunk,
    bodyStyle: TextStyle,
    fontSize: Int,
    isSpeaking: Boolean,
    isTtsActive: Boolean = false,
    isEnglishMode: Boolean,
    isBilingualMode: Boolean,
    isProgressiveMode: Boolean = false,
    bookmark: Bookmark? = null,
    onBookmarkParagraph: (paragraphIndex: Int, text: String, existingBookmark: Bookmark?) -> Unit = { _, _, _ -> },
    onQuoteParagraph: (String) -> Unit = {},
    onSelectWord: (word: String, sentence: String, charOffset: Int) -> Unit = { _, _, _ -> },
    onAnalyzeSentence: (String) -> Unit = {},
    onParagraphClick: (Int) -> Unit = {},
    onTap: () -> Unit = {},
    onPageTap: (Float) -> Unit = {}
) {
    val colors = LocalCustomColors.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val bmColor = bookmark?.let { parseHexColor(it.colorHex) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSpeaking) {
                    Modifier
                        .background(colors.accent.copy(alpha = 0.08f))
                        .border(
                            width = 0.6.dp,
                            color = colors.accent.copy(alpha = 0.35f),
                            shape = AppShapes.small
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                } else if (bookmark != null && bmColor != null && chunk.isStartOfParagraph) {
                    Modifier
                        .background(bmColor.copy(alpha = 0.10f))
                        .border(
                            width = 0.8.dp,
                            color = bmColor.copy(alpha = 0.4f),
                            shape = AppShapes.small
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                } else {
                    Modifier
                }
            )
            .pointerInput(chunk.text, chunk.paragraphIndex, isSpeaking, isTtsActive, bookmark, isEnglishMode, isBilingualMode, isProgressiveMode) {
                detectTapGestures(
                    onLongPress = { offset ->
                        val layout = layoutResult
                        if ((isEnglishMode || isBilingualMode || isProgressiveMode) && layout != null) {
                            val charOffset = layout.getOffsetForPosition(offset)
                            val sentence = extractSentenceAt(chunk.text, charOffset)
                            if (sentence.length >= 8) {
                                onAnalyzeSentence(sentence)
                                return@detectTapGestures
                            }
                        }
                        onBookmarkParagraph(chunk.paragraphIndex, chunk.text, bookmark)
                    },
                    onTap = { offset ->
                        val layout = layoutResult
                        if (layout != null) {
                            val charOffset = layout.getOffsetForPosition(offset)
                            val (word, sentence) = extractWordAndSentence(chunk.text, charOffset)
                            if ((isEnglishMode || isBilingualMode || isProgressiveMode) && word.isNotBlank() && isEnglishWord(word)) {
                                onSelectWord(word, sentence, charOffset)
                                return@detectTapGestures
                            }
                        }
                        if (isTtsActive && !isSpeaking) {
                            onParagraphClick(chunk.paragraphIndex)
                        } else if (isSpeaking) {
                            onTap()
                        } else {
                            val xRatio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onPageTap(xRatio)
                        }
                    }
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (chunk.text.isNotBlank()) {
                Text(
                    text = chunk.text,
                    style = bodyStyle,
                    color = if (isSpeaking) colors.accent else colors.textPrimary,
                    onTextLayout = { layoutResult = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!chunk.subText.isNullOrBlank()) {
                if (chunk.text.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                val subFontSize = (fontSize - 3).coerceAtLeast(12)
                Text(
                    text = chunk.subText,
                    color = colors.textSecondary.copy(alpha = 0.82f),
                    fontSize = subFontSize.sp,
                    lineHeight = (subFontSize * 1.5f).sp,
                    fontFamily = UIFont,
                    letterSpacing = 0.1.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (bookmark != null && bmColor != null && chunk.isEndOfParagraph) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .clip(AppShapes.small)
                        .background(bmColor.copy(alpha = 0.22f))
                        .clickable { onBookmarkParagraph(chunk.paragraphIndex, chunk.text, bookmark) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = TruyenCloudIcons.BookmarkFilled,
                        contentDescription = "Ghi chú dấu trang",
                        tint = bmColor,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (bookmark.note.isNotBlank()) bookmark.note else "Dấu trang",
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


