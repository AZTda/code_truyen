package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.FlashcardArtResponse
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.SavedWord

/**
 * Từ đang mở trong Sổ từ: đầy đủ nghĩa, ngữ cảnh trong truyện, hình minh họa, cùng các hành động phụ (nghe, vẽ thẻ minh
 * họa, luyện ứng dụng) vốn trước đây là nút lớn trên từng dòng.
 */
@Composable
internal fun WordDetailSheet(
    word: SavedWord,
    cachedArt: FlashcardArtResponse?,
    isGeneratingArt: Boolean,
    onSpeak: () -> Unit,
    onRealLife: () -> Unit,
    onGenerateArt: () -> Unit,
    onToggleSuspend: () -> Unit = {},
    onDelete: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val c = LocalCustomColors.current
    val artUrl = cachedArt?.imageUrl?.ifBlank { word.flashcardArtUrl } ?: word.flashcardArtUrl
    val artTitle = cachedArt?.artTitle?.trim().orEmpty()
    val mnemonic = cachedArt?.mnemonic?.ifBlank { word.visualMnemonic } ?: word.visualMnemonic
    val englishDef = cachedArt?.englishDefinition?.ifBlank { word.englishDefinition } ?: word.englishDefinition
    val contextTranslationVi = cachedArt?.contextTranslationVi?.trim().orEmpty()
    val ipa = ipaLine(word)
    val meta = listOf(cefrLabel(word.cefrLevel), word.partOfSpeech.trim()).filter { it.isNotBlank() }.joinToString(" · ")

    IosSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageH),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = word.word,
                        style = AppText.wordTitle.copy(fontSize = 26.sp, lineHeight = 32.sp),
                        color = c.textPrimary
                    )
                    if (meta.isNotBlank()) Text(text = meta, style = AppText.caption, color = c.textSecondary)
                }
                QuietIconButton(TruyenCloudIcons.VolumeUp, "Phát âm từ ${word.word}", onSpeak)
            }

            if (ipa.isNotBlank()) Text(text = ipa, style = AppText.secondary, color = c.textSecondary)

            if (word.contextMeaningVi.isNotBlank()) {
                Text(text = word.contextMeaningVi, style = AppText.bodyStrong.copy(fontSize = 17.sp), color = c.textPrimary)
            }
            if (englishDef.isNotBlank()) Text(text = englishDef, style = AppText.body, color = c.textSecondary)

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
                    if (word.bookTitle.isNotBlank()) {
                        val where = word.bookTitle + if (word.chapterNumber > 0) ", chương ${word.chapterNumber}" else ""
                        Text(text = where, style = AppText.captionRegular, color = c.textTertiary)
                    }
                }
            }

            if (artUrl.isNotBlank()) {
                AsyncImage(
                    model = TruyenCloudApi.getImageProxyUrl(artUrl),
                    contentDescription = "Hình minh họa cho từ ${word.word}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(com.truyencloud.compose.theme.InputShape)
                )
                if (artTitle.isNotBlank()) {
                    Text(text = artTitle, style = AppText.caption, color = c.textSecondary)
                }
                if (mnemonic.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionLabel("Mẹo nhớ")
                        Text(text = mnemonic, style = AppText.body, color = c.textPrimary)
                    }
                }
            } else {
                TonalButton(
                    text = if (isGeneratingArt) "Đang vẽ thẻ minh họa..." else "Vẽ thẻ minh họa",
                    onClick = onGenerateArt,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGeneratingArt,
                    leadingIcon = TruyenCloudIcons.Image
                )
            }

            PrimaryButton(text = "Luyện ứng dụng", onClick = onRealLife, modifier = Modifier.fillMaxWidth())

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                TonalButton(
                    text = if (word.isSuspended) "Bỏ tạm ẩn" else "Tạm ẩn",
                    onClick = onToggleSuspend,
                    modifier = Modifier.weight(1f),
                    leadingIcon = if (word.isSuspended) TruyenCloudIcons.PlayArrow else TruyenCloudIcons.Pause
                )
                TonalButton(
                    text = "Xóa từ",
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    leadingIcon = TruyenCloudIcons.Delete
                )
            }
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}
