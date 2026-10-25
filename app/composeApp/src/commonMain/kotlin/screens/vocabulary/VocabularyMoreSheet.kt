package com.truyencloud.compose.screens.vocabulary

import androidx.compose.runtime.Composable
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.shared.notify.ReviewReminderConfig
import com.truyencloud.shared.repo.StudyConfig

/**
 * Tùy chọn phụ của Từ vựng, gom ra khỏi thanh tiêu đề: thêm từ mới, chỉnh mục tiêu ngày, nhắc ôn hằng ngày và xuất thẻ sang Anki.
 */
@Composable
internal fun VocabularyMoreSheet(
    reminder: ReviewReminderConfig,
    studyConfig: StudyConfig = StudyConfig(),
    canExport: Boolean,
    onOpenAddWord: () -> Unit = {},
    onOpenStudyGoal: () -> Unit = {},
    onOpenReminder: () -> Unit,
    onExportAnki: () -> Unit,
    onDismiss: () -> Unit
) {
    IosSheet(onDismissRequest = onDismiss, title = "Tùy chọn từ vựng") {
        InsetGroup {
            InsetRow(
                title = "Thêm từ vựng mới",
                subtitle = "Nhập từ tiếng Anh, AI tự tra phiên âm và nghĩa",
                icon = TruyenCloudIcons.Wand,
                showChevron = true,
                onClick = onOpenAddWord
            )
            InsetRow(
                title = "Mục tiêu học mỗi ngày",
                subtitle = "${studyConfig.newCardsPerDay} từ mới · tối đa ${studyConfig.maxReviewsPerDay} lượt ôn",
                icon = TruyenCloudIcons.Target,
                value = "${(studyConfig.desiredRetention * 100).toInt()}%",
                showChevron = true,
                onClick = onOpenStudyGoal
            )
            InsetRow(
                title = "Nhắc ôn hằng ngày",
                subtitle = "Báo số từ đang chờ ôn",
                icon = TruyenCloudIcons.Schedule,
                value = if (reminder.enabled) reminder.timeLabel else "Tắt",
                showChevron = true,
                onClick = onOpenReminder
            )
            InsetRow(
                title = "Xuất thẻ cho Anki",
                subtitle = if (canExport) "Chia sẻ tệp TSV để nhập vào Anki" else "Chưa có từ nào để xuất",
                icon = TruyenCloudIcons.AnkiExport,
                iconTint = InsetIconTint.Neutral,
                enabled = canExport,
                onClick = onExportAnki
            )
        }
    }
}
