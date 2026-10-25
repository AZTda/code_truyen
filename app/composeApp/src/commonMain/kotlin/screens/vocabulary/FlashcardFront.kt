package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.shared.api.FlashcardArtResponse
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.practice.PracticeEngine

/**
 * Mặt trước thẻ:
 * - Chiều xuôi (`isReverseCard = false`): hiện từ tiếng Anh, phiên âm và câu trong truyện (từ đích bị che).
 * - Chiều ngược (`isReverseCard = true`): ẩn từ tiếng Anh, chỉ hiện nghĩa tiếng Việt + câu trong truyện bị che từ
 *   để người học chủ động nhớ lại từ vựng (Active Recall).
 */
@Composable
internal fun FlashcardFront(
    word: SavedWord,
    cachedArt: FlashcardArtResponse?,
    isClozeRevealed: Boolean,
    isGeneratingArt: Boolean,
    isReverseCard: Boolean = false,
    onSpeak: () -> Unit,
    onToggleClozeReveal: () -> Unit,
    onGenerateArt: () -> Unit
) {
    val c = LocalCustomColors.current
    val artUrl = cachedArt?.imageUrl?.ifBlank { word.flashcardArtUrl } ?: word.flashcardArtUrl
    val ipa = ipaLine(word)

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusLabel("CEFR ${cefrLabel(word.cefrLevel)}")
                    if (word.partOfSpeech.isNotBlank()) StatusLabel(word.partOfSpeech.trim())
                    if (isReverseCard) StatusLabel("Đoán từ", tone = LabelTone.Accent)
                }
                if (artUrl.isBlank()) {
                    if (isGeneratingArt) {
                        Box(
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "Đang vẽ thẻ minh họa" },
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        QuietIconButton(TruyenCloudIcons.Image, "Vẽ thẻ minh họa", onGenerateArt)
                    }
                }
                if (!isReverseCard) {
                    QuietIconButton(TruyenCloudIcons.VolumeUp, "Phát âm", onSpeak)
                }
            }

            if (artUrl.isNotBlank()) {
                AsyncImage(
                    model = TruyenCloudApi.getImageProxyUrl(artUrl),
                    contentDescription = "Hình minh họa cho từ ${word.word}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(InputShape)
                        .background(c.cardHigh)
                )
            }

            if (isReverseCard) {
                val promptVi = word.contextMeaningVi.ifBlank {
                    word.englishDefinition.ifBlank { "Nhớ lại từ tiếng Anh phù hợp với ngữ cảnh bên dưới" }
                }
                Text(
                    text = promptVi,
                    style = AppText.bodyStrong.copy(fontSize = 22.sp, lineHeight = 30.sp),
                    color = c.textPrimary
                )
                if (word.contextMeaningVi.isNotBlank() && word.englishDefinition.isNotBlank()) {
                    Text(text = word.englishDefinition, style = AppText.secondary, color = c.textSecondary)
                }
                Text(
                    text = PracticeEngine.firstLetterHint(word.word),
                    style = AppText.body.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
                    color = c.accent
                )
            } else {
                Text(
                    text = word.word,
                    style = AppText.wordTitle.copy(fontSize = 32.sp, lineHeight = 40.sp),
                    color = c.textPrimary,
                    maxLines = 2
                )
                if (ipa.isNotBlank()) {
                    Text(text = ipa, style = AppText.secondary, color = c.textSecondary)
                }
            }

            if (word.contextSentence.isNotBlank()) {
                ClozeSentence(word, isClozeRevealed, onToggleClozeReveal)
            }
        }

        Text(
            text = if (isReverseCard) "Chạm vào thẻ để kiểm tra từ tiếng Anh" else "Chạm vào thẻ để xem nghĩa",
            style = AppText.captionRegular,
            color = c.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}

/** Câu gốc trong truyện với từ đích (kể cả dạng chia động từ/số nhiều) bị che hoặc tô đậm khi bấm "Gợi ý". */
@Composable
private fun ClozeSentence(word: SavedWord, isRevealed: Boolean, onToggle: () -> Unit) {
    val c = LocalCustomColors.current
    val sentence = word.contextSentence
    val masked = PracticeEngine.maskWordInSentence(sentence, word.word)
    val styled = buildAnnotatedString {
        append("“")
        if (masked != null) {
            val parts = masked.text.split(PracticeEngine.BLANK)
            parts.forEachIndexed { idx, part ->
                append(part)
                if (idx < parts.lastIndex) {
                    if (isRevealed) {
                        withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.SemiBold)) {
                            append(masked.answer)
                        }
                    } else {
                        append("[ ________ ]")
                    }
                }
            }
        } else {
            append(sentence)
        }
        append("”")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InputShape)
            .background(c.cardHigh)
            .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Câu trong truyện", Modifier.weight(1f))
            TextActionButton(text = if (isRevealed) "Ẩn từ" else "Gợi ý", onClick = onToggle)
        }
        Text(
            text = styled,
            style = AppText.body.copy(fontFamily = ReaderFont),
            color = c.textPrimary,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}
