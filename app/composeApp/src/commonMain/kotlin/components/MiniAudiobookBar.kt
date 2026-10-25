package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.screens.reader.ReaderUiState
import com.truyencloud.compose.screens.reader.TtsVoiceChoices
import com.truyencloud.compose.screens.reader.ttsSource
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayTitle

/**
 * Trình phát Audiobook nổi (Mini Floating Audio Player) hiển thị ngoài màn hình chính (Tủ sách, Khám phá, Chi tiết truyện)
 * khi người dùng đang nghe TTS rồi thoát khỏi màn đọc. Toàn bộ nút dùng icon vector chuẩn [TruyenCloudIcons].
 */
@Composable
fun MiniAudiobookBar(
    state: ReaderUiState,
    onOpenReader: (Novel, Chapter) -> Unit,
    onPlayPause: () -> Unit,
    onPrevParagraph: () -> Unit,
    onNextParagraph: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val novel = state.novel ?: return
    val chapter = state.currentChapter ?: return
    if (!state.isTtsActive) return

    val colors = LocalCustomColors.current
    val totalParagraphs = state.ttsSource().size.coerceAtLeast(1)
    val currentPara = (state.ttsCurrentParagraph + 1).coerceIn(1, totalParagraphs)
    val progress = (currentPara.toFloat() / totalParagraphs.toFloat()).coerceIn(0f, 1f)
    val voiceName = TtsVoiceChoices.heard(state.ttsVoiceProfileId, state.ttsVoiceTone).name
    val subtitle = state.ttsPreparingLabel ?: "Ch.${chapter.chapterNumber} · Đoạn $currentPara/$totalParagraphs · $voiceName"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageH, vertical = 6.dp)
            .clip(CardShape)
            .background(colors.cardHigh)
            .border(1.dp, colors.outline, CardShape)
            .clickable(role = Role.Button, onClick = { onOpenReader(novel, chapter) })
    ) {
        ProgressBar3(progress = progress, modifier = Modifier.fillMaxWidth())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (state.ttsIsPlaying) TruyenCloudIcons.GraphicEq else TruyenCloudIcons.Headphones,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = novel.displayTitle,
                    style = AppText.secondaryStrong,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = AppText.captionRegular,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietIconButton(
                    icon = TruyenCloudIcons.SkipPrevious,
                    contentDescription = "Đoạn trước",
                    onClick = onPrevParagraph,
                    iconSize = 20.dp
                )
                QuietIconButton(
                    icon = if (state.ttsIsPlaying) TruyenCloudIcons.PauseFilled else TruyenCloudIcons.PlayFilled,
                    contentDescription = if (state.ttsIsPlaying) "Tạm dừng" else "Phát tiếp",
                    onClick = onPlayPause,
                    tint = colors.accent,
                    iconSize = 22.dp
                )
                QuietIconButton(
                    icon = TruyenCloudIcons.SkipNext,
                    contentDescription = "Đoạn tiếp",
                    onClick = onNextParagraph,
                    iconSize = 20.dp
                )
                QuietIconButton(
                    icon = TruyenCloudIcons.Close,
                    contentDescription = "Tắt trình phát",
                    onClick = onClose,
                    tint = colors.textTertiary,
                    iconSize = 18.dp
                )
            }
        }
    }
}
