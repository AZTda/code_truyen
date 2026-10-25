package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.SecondaryButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.srs.StudyDay
import kotlin.math.max

/**
 * Màn hình khi hết thẻ trong phiên Ôn thẻ.
 *
 * Bản cũ: hết thẻ đến hạn thì lặng lẽ lùi về "tất cả từ" và cho ôn tiếp, không bao giờ kết thúc, và mọi lượt
 * ôn thêm đó đều bị ghi vào lịch FSRS làm khoảng cách phình ra. Giờ: báo hoàn thành, nói khi nào có thẻ kế tiếp,
 * và chỉ ôn nhồi khi người học CHỦ ĐỘNG chọn (ôn nhồi không đổi lịch).
 */
@Composable
internal fun FlashcardsCompleteView(
    uiState: VocabularyUiState,
    onStartCram: () -> Unit,
    onExitCram: () -> Unit,
    onOpenPractice: () -> Unit,
    onUndo: () -> Unit = {},
    bottomPadding: Dp = Spacing.lg
) {
    val c = LocalCustomColors.current
    val snap = uiState.snapshot
    val cram = uiState.isCramMode
    val now = getCurrentEpochMillis()
    val nextDue = nextDueLabel(uiState.allWords, now)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageH)
            .padding(bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = TruyenCloudIcons.CheckCircle,
            contentDescription = null,
            tint = c.success,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = if (cram) "Xong lượt ôn nhồi" else "Hoàn thành hôm nay",
            style = AppText.sectionHeader,
            color = c.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = if (cram) "Lượt ôn nhồi không thay đổi lịch ôn của bạn."
            else "Bạn đã ôn ${snap.reviewedToday} thẻ hôm nay" +
                if (snap.streakDays > 0) ", chuỗi ${snap.streakDays} ngày." else ".",
            style = AppText.secondary,
            color = c.textSecondary,
            textAlign = TextAlign.Center
        )

        if (!cram) {
            if (nextDue != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "Thẻ kế tiếp đến hạn: $nextDue.",
                    style = AppText.secondary,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
            if (snap.newCount > 0) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "Còn ${snap.newCount} từ mới chưa học, sẽ vào hàng đợi khi đến lượt.",
                    style = AppText.secondary,
                    color = c.textTertiary,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(Spacing.xl))

        if (uiState.allWords.isNotEmpty()) {
            PrimaryButton(
                text = if (cram) "Ôn nhồi lượt khác" else "Ôn nhồi thêm",
                onClick = onStartCram,
                modifier = Modifier.fillMaxWidth()
            )
            if (!cram) {
                Text(
                    text = "Ôn nhồi là xem lại thẻ đã học, không đổi lịch ôn.",
                    style = AppText.captionRegular,
                    color = c.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
            Spacer(Modifier.height(Spacing.md))
        }
        if (cram) {
            SecondaryButton(text = "Quay lại thẻ hôm nay", onClick = onExitCram)
        } else {
            SecondaryButton(text = "Luyện tập chủ động", onClick = onOpenPractice)
        }
        if (uiState.canUndoReview) {
            Spacer(Modifier.height(Spacing.sm))
            com.truyencloud.compose.components.TextActionButton(
                text = "Hoàn tác thẻ vừa chấm",
                onClick = onUndo
            )
        }
    }
}

/** "3 giờ nữa", "ngày mai", "5 ngày nữa"; null nếu không còn thẻ nào có lịch. */
private fun nextDueLabel(words: List<SavedWord>, now: Long): String? {
    val next = words
        .filter { !it.isSuspended && !it.isNeverStudied && it.dueEpoch > now }
        .minOfOrNull { it.dueEpoch } ?: return null
    val delta = next - now
    val minutes = delta / 60_000L
    return when {
        minutes < 60L -> "${max(1L, minutes)} phút nữa"
        minutes < 20L * 60L -> "${minutes / 60L} giờ nữa"
        else -> {
            val days = StudyDay.dayIndex(next) - StudyDay.dayIndex(now)
            if (days <= 1L) "ngày mai" else "$days ngày nữa"
        }
    }
}
