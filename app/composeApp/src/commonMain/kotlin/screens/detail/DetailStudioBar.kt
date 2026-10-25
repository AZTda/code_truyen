package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.screens.reader.GlossarySheet
import com.truyencloud.compose.screens.reader.ReaderSheetBackdrop
import com.truyencloud.compose.screens.reader.TtsVoiceChoices
import com.truyencloud.compose.screens.reader.VocabularyDeckSheet
import com.truyencloud.compose.screens.settings.TtsVoicePickerSheet
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.TruyenCloudApi

/**
 * Thanh công cụ Studio ngay dưới [NovelActionBar]: Xuất EPUB (.epub), Quản lý Bảng thuật ngữ (Glossary),
 * Chọn giọng đọc VieNeu-TTS và Ôn từ vựng theo truyện — toàn bộ dùng icon vector chuẩn TruyenCloudIcons.
 */
@Composable
internal fun DetailStudioBar(
    epubReadyCount: Int,
    glossaryCount: Int,
    voiceName: String,
    novelVocabCount: Int,
    totalVocabCount: Int,
    onExportEpub: () -> Unit,
    onOpenGlossary: () -> Unit,
    onOpenVoicePicker: () -> Unit,
    onOpenVocabSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip8(
            label = if (epubReadyCount > 0) "Xuất EPUB ($epubReadyCount ch.)" else "Xuất EPUB",
            selected = epubReadyCount > 0,
            leadingIcon = TruyenCloudIcons.BookDown,
            onClick = onExportEpub
        )
        FilterChip8(
            label = "Thuật ngữ",
            trailingText = if (glossaryCount > 0) "$glossaryCount" else null,
            selected = glossaryCount > 0,
            leadingIcon = TruyenCloudIcons.GlossaryBook,
            onClick = onOpenGlossary
        )
        FilterChip8(
            label = "Giọng: $voiceName",
            selected = false,
            leadingIcon = TruyenCloudIcons.RecordVoiceOver,
            onClick = onOpenVoicePicker
        )
        val vocabCount = if (novelVocabCount > 0) novelVocabCount else totalVocabCount
        FilterChip8(
            label = if (novelVocabCount > 0) "Từ vựng truyện" else "Ôn từ vựng",
            trailingText = if (vocabCount > 0) "$vocabCount" else null,
            selected = novelVocabCount > 0,
            leadingIcon = TruyenCloudIcons.Flashcards,
            onClick = onOpenVocabSheet
        )
    }
}

/** Khối chọn giọng VieNeu-TTS và dựng sẵn Audio trong hộp thoại Tải & Dịch ngầm. */
@Composable
internal fun BatchTtsVoiceSection(
    studioState: DetailStudioUiState,
    fromChapter: Int,
    toChapter: Int,
    isQueuing: Boolean,
    onOpenVoicePicker: () -> Unit,
    onPrerenderAudio: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val activeVoice = studioState.activeVoice
    val prerenderCount = (toChapter - fromChapter + 1).coerceIn(1, 20)
    val prerenderEnd = fromChapter + prerenderCount - 1

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.card)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpenVoicePicker),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TruyenCloudIcons.RecordVoiceOver,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Giọng đọc AI: ${activeVoice.name}",
                        style = AppText.secondaryStrong,
                        color = colors.textPrimary
                    )
                    Text(
                        text = TtsVoiceChoices.subtitle(activeVoice),
                        style = AppText.captionRegular,
                        color = colors.textSecondary
                    )
                }
            }
            Text(
                text = "Đổi giọng",
                style = AppText.secondaryStrong,
                color = colors.accent
            )
        }
        TonalButton(
            text = "Dựng sẵn Audio ${activeVoice.name} (Ch.$fromChapter → $prerenderEnd)",
            onClick = { onPrerenderAudio(fromChapter, prerenderEnd) },
            enabled = !isQueuing,
            leadingIcon = TruyenCloudIcons.Headphones,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Các hộp thoại / tờ trượt Studio gắn tại màn Chi tiết truyện. */
@Composable
internal fun DetailStudioModals(
    studioState: DetailStudioUiState,
    studio: DetailStudioCoordinator,
    onRetranslateOrBatch: () -> Unit,
    onOpenReaderForLookup: () -> Unit
) {
    if (studioState.glossary.visible) {
        ReaderSheetBackdrop(onDismiss = studio::closeGlossary) {
            GlossarySheet(
                state = studioState.glossary,
                canRetranslate = TruyenCloudApi.authToken.isNotBlank(),
                onRetranslate = {
                    studio.closeGlossary()
                    onRetranslateOrBatch()
                },
                onSaveTarget = studio::saveGlossaryTarget,
                onTogglePin = studio::toggleGlossaryPin,
                onDelete = studio::deleteGlossaryTerm,
                onAdd = studio::addGlossaryTerm,
                onClearAll = studio::clearAllGlossaryTerms,
                onOpenLookup = {
                    studio.closeGlossary()
                    onOpenReaderForLookup()
                },
                onDismissMessage = studio::dismissGlossaryMessage,
                onDismiss = studio::closeGlossary,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (studioState.showVoicePicker) {
        TtsVoicePickerSheet(
            voiceProfiles = studioState.voiceProfiles,
            ttsVoiceProfileId = studioState.ttsVoiceProfileId,
            ttsVoiceTone = studioState.ttsVoiceTone,
            onVoiceProfileChange = studio::selectVoiceProfile,
            onDismiss = studio::closeVoicePicker
        )
    }

    if (studioState.showVocabSheet) {
        VocabularyDeckSheet(
            words = studioState.novelWords,
            allWords = studioState.allWords,
            onReviewWord = studio::reviewWord,
            onSpeakWord = studio::speakWord,
            onDismiss = studio::closeVocabSheet
        )
    }
}
