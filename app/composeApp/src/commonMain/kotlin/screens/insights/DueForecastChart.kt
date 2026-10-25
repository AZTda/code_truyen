package com.truyencloud.compose.screens.insights

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

private val BarShape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)

/**
 * Dự báo từ đến hạn ôn: một cột "Quá hạn" rồi bảy ngày học. Số từ ghi trên đầu cột, nhãn ngày dưới chân cột (tối đa hai
 * dòng vì cột chỉ rộng ~33dp). Màu chỉ nhấn nhẹ: cột quá hạn dùng màu cảnh báo và luôn có nhãn chữ riêng.
 */
@Composable
fun DueForecastChart(forecast: List<DueForecastDay>, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    val maxCount = forecast.maxOfOrNull { it.count } ?: 0
    val grow = remember { Animatable(0f) }
    LaunchedEffect(forecast) { grow.animateTo(1f, AppAnimations.slowTween) }

    FlatCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp)) {
        // Cột hẹp, chiều cao cố định: giới hạn phóng chữ để nhãn hai dòng vẫn vừa
        CappedFontScale(max = 1.15f) {
            Row(
                modifier = Modifier.fillMaxWidth().height(168.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                forecast.forEach { day ->
                    val spoken = when {
                        day.isOverdue -> "Quá hạn"
                        day.isToday -> "Hôm nay"
                        else -> day.dayLabel
                    }
                    val fraction = if (maxCount > 0 && day.count > 0) {
                        (day.count.toFloat() / maxCount).coerceIn(0.04f, 1f) * grow.value
                    } else 0f
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clearAndSetSemantics { contentDescription = "$spoken: ${day.count} từ" },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = InsightsFormat.compact(day.count),
                            style = AppText.caption,
                            color = if (day.count > 0) colors.textPrimary else colors.textTertiary,
                            maxLines = 1,
                            softWrap = false
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .width(20.dp)
                                .clip(BarShape)
                                .background(colors.hairline),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(fraction)
                                    .background(if (day.isOverdue) colors.warning else colors.accent)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(modifier = Modifier.height(40.dp), contentAlignment = Alignment.TopCenter) {
                            Text(
                                text = day.dayLabel,
                                style = if (day.isToday) AppText.caption else AppText.captionRegular,
                                color = when {
                                    day.isOverdue -> colors.warning
                                    day.isToday -> colors.accent
                                    else -> colors.textSecondary
                                },
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Clip
                            )
                        }
                    }
                }
            }
        }
    }
}
