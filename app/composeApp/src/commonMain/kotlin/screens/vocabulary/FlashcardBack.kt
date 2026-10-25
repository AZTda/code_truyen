package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.shared.api.CollocationItem
import com.truyencloud.shared.api.FlashcardArtResponse
import com.truyencloud.shared.data.SavedWord

/**
 * Mặt sau thẻ: nghĩa tiếng Việt lớn và rõ, rồi định nghĩa, mẹo nhớ, cụm từ, đồng / trái nghĩa và câu trong truyện.
 * Tiêu đề từng khối là chữ 13sp thường (không in hoa, không biểu tượng cảm xúc).
 */
@Composable
internal fun FlashcardBack(
    word: SavedWord,
    cachedArt: FlashcardArtResponse?,
    collocations: List<CollocationItem>,
    onSpeak: () -> Unit
) {
    val c = LocalCustomColors.current
    val synonyms: List<String> = cachedArt?.synonyms ?: emptyList()
    val antonyms: List<String> = cachedArt?.antonyms ?: emptyList()
    val englishDef = cachedArt?.englishDefinition?.ifBlank { word.englishDefinition } ?: word.englishDefinition
    val mnemonic = cachedArt?.mnemonic?.ifBlank { word.visualMnemonic } ?: word.visualMnemonic
    val contextTranslationVi = cachedArt?.contextTranslationVi ?: ""

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = word.word,
                        style = AppText.wordTitle.copy(fontSize = 24.sp, lineHeight = 30.sp),
                        color = c.textPrimary,
                        maxLines = 2
                    )
                    if (word.partOfSpeech.isNotBlank()) {
                        Text(text = word.partOfSpeech.trim(), style = AppText.caption, color = c.textSecondary)
                    }
                }
                QuietIconButton(TruyenCloudIcons.VolumeUp, "Phát âm", onSpeak)
            }

            Text(
                text = word.contextMeaningVi,
                style = AppText.bodyStrong.copy(fontSize = 18.sp, lineHeight = 26.sp),
                color = c.textPrimary
            )
            if (englishDef.isNotBlank()) {
                Text(text = englishDef, style = AppText.body, color = c.textSecondary)
            }

            if (mnemonic.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InputShape)
                        .background(c.cardHigh)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SectionLabel("Mẹo nhớ")
                    Text(text = mnemonic, style = AppText.body, color = c.textPrimary)
                }
            }

            if (collocations.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Cụm từ hay gặp")
                    collocations.forEach { col ->
                        Column {
                            Text(text = col.phrase, style = AppText.bodyStrong, color = c.textPrimary)
                            if (col.meaning.isNotBlank()) {
                                Text(text = col.meaning, style = AppText.secondary, color = c.textSecondary)
                            }
                        }
                    }
                }
            }

            if (synonyms.isNotEmpty() || antonyms.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (synonyms.isNotEmpty()) WordGroup("Đồng nghĩa", synonyms, Modifier.weight(1f))
                    if (antonyms.isNotEmpty()) WordGroup("Trái nghĩa", antonyms, Modifier.weight(1f))
                }
            }

            if (word.contextSentence.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionLabel("Câu trong truyện")
                    Text(
                        text = "“${word.contextSentence}”",
                        style = AppText.body.copy(fontFamily = ReaderFont),
                        color = c.textPrimary
                    )
                    if (contextTranslationVi.isNotBlank()) {
                        Text(text = contextTranslationVi, style = AppText.secondary, color = c.textSecondary)
                    }
                }
            }

            FlashcardExtraContexts(word)
        }

        Text(
            text = "Bạn nhớ từ này đến đâu?",
            style = AppText.captionRegular,
            color = c.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}

@Composable
private fun WordGroup(label: String, words: List<String>, modifier: Modifier = Modifier) {
    val c = LocalCustomColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(label)
        Text(text = words.joinToString(", "), style = AppText.body, color = c.textPrimary)
    }
}
