package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.ProgressBar3
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.SavedWord

/** Dải báo đang ôn theo cuốn truyện / chủ đề, có nút quay về "Tất cả từ". */
@Composable
internal fun FlashcardContextBanner(uiState: VocabularyUiState, onClearContextFilter: () -> Unit) {
    if (uiState.selectedBook == null && uiState.selectedDomain == null) return
    val label = if (uiState.selectedBook != null) {
        "Đang ôn theo truyện: ${uiState.selectedBook}"
    } else {
        "Đang ôn theo chủ đề: ${VocabularyDomains.titleOf(uiState.selectedDomain)}"
    }
    ContextBanner(label = label, actionLabel = "Xem tất cả từ", onAction = onClearContextFilter)
}

/** Dải báo ôn nhồi: nhắc rõ lượt này KHÔNG đổi lịch ôn. */
@Composable
internal fun FlashcardCramBanner(onExit: () -> Unit) {
    ContextBanner(label = "Ôn nhồi, không đổi lịch ôn", actionLabel = "Thoát", onAction = onExit)
}

@Composable
private fun ContextBanner(label: String, actionLabel: String, onAction: () -> Unit) {
    val c = LocalCustomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm)
            .clip(InputShape)
            .background(c.accentSoft)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = AppText.secondary,
            color = c.onAccentSoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextActionButton(text = actionLabel, onClick = onAction, color = c.onAccentSoft)
    }
}

/** Số thẻ trong phiên, nhãn trạng thái của thẻ hiện tại, nút hoàn tác / đổi chiều thẻ / tự đọc / trộn và thanh tiến độ 3dp. */
@Composable
internal fun FlashcardProgressHeader(
    uiState: VocabularyUiState,
    deck: List<SavedWord>,
    currentWord: SavedWord,
    onToggleAutoSpeak: () -> Unit,
    onShuffle: () -> Unit,
    onToggleReverseCard: () -> Unit = {},
    onUndo: () -> Unit = {}
) {
    val c = LocalCustomColors.current
    // Số thứ tự tính theo PHIÊN (đã chấm + còn lại), không theo vị trí trong mảng đang co lại sau mỗi lần chấm.
    val done = uiState.reviewedCountSession
    val total = done + deck.size
    val (stateText, stateTone) = when {
        currentWord.stability >= 21.0 -> "Đã thuộc" to LabelTone.Success
        currentWord.stability > 0.0 -> "Đang ôn, nhớ ${currentWord.stability.toInt()} ngày" to LabelTone.Accent
        else -> "Thẻ mới" to LabelTone.Neutral
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Thẻ ${done + 1} / $total", style = AppText.bodyStrong, color = c.textPrimary)
                StatusLabel(text = stateText, tone = stateTone, modifier = Modifier.padding(top = 2.dp))
            }
            if (uiState.canUndoReview) {
                TextActionButton(text = "Hoàn tác", onClick = onUndo, color = c.accent)
            }
            QuietIconButton(
                icon = TruyenCloudIcons.SwapVert,
                contentDescription = if (uiState.isReverseCard) "Đổi về chiều xuôi (Nhìn từ đoán nghĩa)" else "Đổi sang chiều ngược (Nhìn nghĩa đoán từ)",
                onClick = onToggleReverseCard,
                tint = if (uiState.isReverseCard) c.accent else c.textSecondary
            )
            QuietIconButton(
                icon = if (uiState.isAutoSpeak) TruyenCloudIcons.VolumeUp else TruyenCloudIcons.VolumeOff,
                contentDescription = if (uiState.isAutoSpeak) "Tắt tự đọc" else "Bật tự đọc",
                onClick = onToggleAutoSpeak,
                tint = if (uiState.isAutoSpeak) c.accent else c.textSecondary
            )
            QuietIconButton(
                icon = TruyenCloudIcons.Shuffle,
                contentDescription = if (uiState.isShuffled) "Bỏ trộn thẻ" else "Trộn thẻ",
                onClick = onShuffle,
                tint = if (uiState.isShuffled) c.accent else c.textSecondary
            )
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        ProgressBar3(progress = done.toFloat() / total.coerceAtLeast(1))
    }
}
