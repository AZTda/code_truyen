package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.ProgressBar3
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/** Kết quả sau khi nộp bài: số câu đúng, phần trăm (kèm thanh tiến độ 3dp) và một lời nhận xét ngắn. */
@Composable
fun ComprehensionScoreCard(
    score: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val percentage = (score * 100) / maxOf(1, total)

    FlatCard(modifier = modifier.fillMaxWidth(), outlined = true) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = "Kết quả đọc hiểu", style = AppText.caption, color = colors.textSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$score / $total câu đúng ($percentage%)",
                style = AppText.screenTitle,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            ProgressBar3(progress = percentage / 100f, minVisible = 0.02f)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (percentage >= 75) "Xuất sắc! Khả năng nắm bắt cốt truyện rất tốt."
                else "Cố gắng lên! Hãy xem lại các câu giải thích phía dưới.",
                style = AppText.secondary,
                color = colors.textSecondary
            )
        }
    }
}
