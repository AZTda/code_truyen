package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.ProgressBar3
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import androidx.compose.ui.unit.dp

/**
 * Thẻ "Hôm nay" đầu Sổ từ: tiến độ ôn trong ngày (thanh 3dp), tỉ lệ nhớ 30 ngày và số từ khó nhớ. Chỉ con số thật từ
 * lịch ôn; không điểm thưởng hay cấp độ.
 */
@Composable
internal fun TodaySummaryCard(
    uiState: VocabularyUiState,
    modifier: Modifier = Modifier,
    onOpenStudyGoal: () -> Unit = {},
    onOpenAddWord: () -> Unit = {}
) {
    val c = LocalCustomColors.current
    val snap = uiState.snapshot
    val retentionPct = (snap.retention30d * 100).toInt()
    val retentionColor = when {
        snap.retention30d >= 0.85 -> c.success
        snap.retention30d >= 0.70 -> c.warning
        else -> c.error
    }

    FlatCard(modifier = modifier.fillMaxWidth(), onClick = onOpenStudyGoal) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Hôm nay · ${uiState.studyConfig.newCardsPerDay} từ mới/ngày",
                    style = AppText.bodyStrong,
                    color = c.textPrimary
                )
                if (snap.retention30d > 0.0) {
                    Text(
                        text = "Tỉ lệ nhớ $retentionPct%",
                        style = AppText.secondaryStrong,
                        color = retentionColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            ProgressBar3(
                progress = snap.goalProgress,
                color = if (snap.isGoalReached) c.success else c.accent
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        snap.isGoalReached -> "Đã xong mục tiêu hôm nay"
                        snap.dailyGoal > 0 -> "Đã ôn ${snap.reviewedToday}/${snap.dailyGoal} thẻ"
                        else -> "Hôm nay chưa có thẻ cần ôn"
                    },
                    style = AppText.secondary,
                    color = if (snap.isGoalReached) c.success else c.textSecondary,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (snap.streakDays > 0) {
                    Text(
                        text = "Chuỗi ${snap.streakDays} ngày",
                        style = AppText.secondary,
                        color = c.textSecondary,
                        maxLines = 1
                    )
                }
            }

            if (snap.leechWords > 0) {
                Text(
                    text = "${snap.leechWords} từ đang khó nhớ. Thử đổi mẹo ghi nhớ hoặc luyện điền khuyết.",
                    style = AppText.secondary,
                    color = c.warning
                )
            }
        }
    }
}
