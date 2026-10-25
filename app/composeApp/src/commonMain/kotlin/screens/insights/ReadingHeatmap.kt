package com.truyencloud.compose.screens.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.theme.AppPalette
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

private val WEEKDAY_LABELS = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
private val LEVEL_ALPHA = floatArrayOf(0.30f, 0.50f, 0.75f, 1f)

/**
 * Lưới hoạt động đọc theo tuần (mỗi cột là 7 ngày liền nhau, cột cuối kết thúc ở hôm nay; mỗi hàng luôn là cùng một thứ
 * trong tuần nên nhãn bên trái đúng cho mọi cột). Ô đậm dần theo số phút đọc, so với ngày đọc nhiều nhất trong khoảng.
 * Cỡ ô co giãn theo bề rộng thẻ nên không cần cuộn ngang.
 */
@Composable
fun ReadingHeatmap(days: List<HeatmapDay>, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    val maxValue = days.maxOfOrNull { it.value } ?: 0
    val weeks = remember(days) { days.chunked(7) }
    val activeDays = remember(days) { days.count { it.value > 0 } }
    val summary = "Hoạt động đọc ${weeks.size} tuần gần nhất: $activeDays ngày có đọc"

    FlatCard(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = summary },
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 3.dp
                val labelWidth = 24.dp
                val cols = weeks.size.coerceAtLeast(1)
                val cell = ((maxWidth - labelWidth - gap * cols) / cols).coerceIn(8.dp, 24.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    // Nhãn thứ: lấy từ tuần đầu tiên để đúng với từng hàng
                    CappedFontScale(max = 1.15f) {
                        Column(modifier = Modifier.width(labelWidth), verticalArrangement = Arrangement.spacedBy(gap)) {
                            weeks.firstOrNull().orEmpty().forEach { day ->
                                Box(modifier = Modifier.height(cell).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                    Text(
                                        text = WEEKDAY_LABELS[day.dayOfWeek.coerceIn(0, 6)],
                                        style = AppText.captionRegular,
                                        color = colors.textTertiary,
                                        maxLines = 1,
                                        modifier = Modifier.wrapContentHeight(unbounded = true)
                                    )
                                }
                            }
                        }
                    }
                    weeks.forEach { week ->
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            week.forEach { day ->
                                Box(
                                    modifier = Modifier
                                        .size(cell)
                                        .clip(RoundedCornerShape(cell / 5))
                                        .background(heatColor(day.value, maxValue, colors))
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HeatmapLegend(modifier = Modifier.align(Alignment.End))
        }
    }
}

/** Ô trống = màu đường kẻ; có đọc thì bốn mức đậm dần của màu nhấn. */
private fun heatColor(value: Int, maxValue: Int, colors: AppPalette): Color {
    if (value <= 0 || maxValue <= 0) return colors.hairline
    val intensity = value.toFloat() / maxValue
    val level = when {
        intensity < 0.25f -> 0
        intensity < 0.5f -> 1
        intensity < 0.75f -> 2
        else -> 3
    }
    return colors.accent.copy(alpha = LEVEL_ALPHA[level])
}

@Composable
private fun HeatmapLegend(modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    CappedFontScale(max = 1.15f) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Ít", style = AppText.captionRegular, color = colors.textTertiary)
            Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(colors.hairline))
            LEVEL_ALPHA.forEach { alpha ->
                Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(colors.accent.copy(alpha = alpha)))
            }
            Text("Nhiều", style = AppText.captionRegular, color = colors.textTertiary)
        }
    }
}
