package com.truyencloud.compose.screens.insights

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.SectionHeader
import com.truyencloud.compose.components.StatItem
import com.truyencloud.compose.components.StatRow
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Hàng số liệu đầu trang: MỘT thẻ với ba con số thật (thời gian đọc, chương đã đọc, từ đã lưu).
 * Không điểm, không cấp độ, không chuỗi ngày. Dưới một giờ thì đổi sang phút để khỏi hiện "0 giờ".
 */
@Composable
fun HeroStatsRow(state: InsightsUiState, modifier: Modifier = Modifier) {
    val reading = if (state.totalMinutesRead < 60) {
        StatItem(InsightsFormat.count(state.totalMinutesRead), "Phút đọc")
    } else {
        StatItem(InsightsFormat.hours(state.totalMinutesRead), "Giờ đọc")
    }
    StatRow(
        items = listOf(
            reading,
            StatItem(InsightsFormat.count(state.totalChaptersRead, compactFrom = 100_000), "Chương đã đọc"),
            StatItem(InsightsFormat.count(state.totalWords, compactFrom = 100_000), "Từ đã lưu")
        ),
        modifier = modifier
    )
}

/**
 * Một khối của trang Thống kê: tiêu đề 17sp (viết hoa chữ đầu câu), dòng chú thích 13sp tuỳ chọn giải thích biểu đồ
 * đang đo gì, rồi nội dung. Lề ngang do trang đặt.
 */
@Composable
fun InsightsSection(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    content: @Composable () -> Unit
) {
    val colors = LocalCustomColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = title)
        if (caption != null) {
            Text(
                text = caption,
                style = AppText.secondary,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        content()
    }
}
