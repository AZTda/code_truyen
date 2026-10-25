package com.truyencloud.compose.screens.insights

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.data.DailyReviewStat

private val ChartHeight = 128.dp
private val AxisWidth = 40.dp

/**
 * Tỉ lệ trả lời đúng mỗi ngày có ôn (đường liền, màu nhấn) cùng số lượt ôn (đường đứt, màu phụ) trên cùng một khung 0-100%.
 * Hai đường khác nhau cả về màu lẫn nét nên không phụ thuộc màu; trục dọc có nhãn, trục ngang ghi ngày đầu và ngày cuối.
 */
@Composable
fun ReviewAccuracyChart(stats: List<DailyReviewStat>, modifier: Modifier = Modifier) {
    if (stats.isEmpty()) return
    val colors = LocalCustomColors.current
    val grow = remember { Animatable(0f) }
    LaunchedEffect(stats) { grow.animateTo(1f, AppAnimations.slowTween) }
    val maxTotal = stats.maxOf { it.total }.coerceAtLeast(1)
    val latest = stats.last()
    val summary = "Tỉ lệ trả lời đúng mỗi ngày, gần nhất ${InsightsFormat.percent(latest.accuracy)}"

    val gridColor = colors.hairline
    val accuracyColor = colors.accent
    val totalColor = colors.textTertiary

    FlatCard(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = summary },
        contentPadding = PaddingValues(16.dp)
    ) {
        CappedFontScale(max = 1.15f) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().height(ChartHeight)) {
                    Column(
                        modifier = Modifier.width(AxisWidth).fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("100%", "50%", "0%").forEach { label ->
                            Box(modifier = Modifier.height(16.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                Text(label, style = AppText.captionRegular, color = colors.textTertiary, maxLines = 1)
                            }
                        }
                    }
                    Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        val padV = 8.dp.toPx()
                        val padH = 6.dp.toPx()
                        val chartW = size.width - padH * 2
                        val chartH = size.height - padV * 2
                        val base = padV + chartH

                        listOf(0f, 0.5f, 1f).forEach { f ->
                            val y = padV + (1f - f) * chartH
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        }

                        fun xAt(i: Int): Float =
                            if (stats.size == 1) size.width / 2f else padH + i.toFloat() / (stats.size - 1) * chartW

                        fun series(valueAt: (DailyReviewStat) -> Float): Path = Path().apply {
                            stats.forEachIndexed { i, s ->
                                val y = base - valueAt(s).coerceIn(0f, 1f) * chartH * grow.value
                                if (i == 0) moveTo(xAt(i), y) else lineTo(xAt(i), y)
                            }
                        }

                        if (stats.size > 1) {
                            drawPath(
                                path = series { it.total.toFloat() / maxTotal },
                                color = totalColor,
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    cap = StrokeCap.Butt,
                                    join = StrokeJoin.Round,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                                )
                            )
                            drawPath(
                                path = series { it.accuracy.toFloat() },
                                color = accuracyColor,
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                        drawCircle(
                            color = accuracyColor,
                            radius = 4.dp.toPx(),
                            center = Offset(xAt(stats.lastIndex), base - latest.accuracy.toFloat().coerceIn(0f, 1f) * chartH * grow.value)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = AxisWidth, top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(InsightsFormat.dayMonth(stats.first().dateKey), style = AppText.captionRegular, color = colors.textTertiary)
                    if (stats.size > 1) {
                        Text(InsightsFormat.dayMonth(latest.dateKey), style = AppText.captionRegular, color = colors.textTertiary)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LegendLine(color = accuracyColor, dashed = false, label = "Tỉ lệ trả lời đúng (theo trục 0-100%)")
                    LegendLine(color = totalColor, dashed = true, label = "Số lượt ôn (so với ngày ôn nhiều nhất)")
                }
            }
        }
    }
}

@Composable
private fun LegendLine(color: Color, dashed: Boolean, label: String) {
    val colors = LocalCustomColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (dashed) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) { Box(Modifier.width(6.dp).height(3.dp).clip(CircleShape).background(color)) }
            }
        } else {
            Box(Modifier.width(15.dp).height(3.dp).clip(CircleShape).background(color))
        }
        Text(label, style = AppText.captionRegular, color = colors.textSecondary, textAlign = TextAlign.Start, maxLines = 1)
    }
}
