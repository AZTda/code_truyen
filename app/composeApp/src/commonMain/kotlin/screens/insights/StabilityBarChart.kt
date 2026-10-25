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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Phân bố độ bền trí nhớ: mỗi hàng một khoảng thời gian, thanh ngang tỉ lệ với số từ trong khoảng đó, số từ ghi ngay
 * bên phải (không bắt người đọc suy ra từ độ dài thanh).
 */
@Composable
fun StabilityBarChart(buckets: List<StabilityBucket>, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    val maxFraction = buckets.maxOfOrNull { it.fraction } ?: 0f
    val grow = remember { Animatable(0f) }
    LaunchedEffect(buckets) { grow.animateTo(1f, AppAnimations.slowTween) }

    FlatCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        CappedFontScale(max = 1.2f) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                buckets.forEach { bucket ->
                    val share = when {
                        bucket.count <= 0 || maxFraction <= 0f -> 0f
                        else -> (bucket.fraction / maxFraction).coerceIn(0.03f, 1f)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = bucket.label,
                            style = AppText.captionRegular,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(76.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(colors.hairline)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(share * grow.value)
                                    .background(colors.accent)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = InsightsFormat.count(bucket.count, compactFrom = 10_000),
                            style = AppText.caption,
                            color = colors.textPrimary,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            modifier = Modifier.width(40.dp)
                        )
                    }
                }
            }
        }
    }
}
