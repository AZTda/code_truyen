package com.truyencloud.compose.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Khối khung xương khi chờ dữ liệu: màu thẻ, "thở" nhẹ giữa card và cardHigh với chu kỳ 1,4 giây (không quét nhanh).
 * Kích thước cố định theo [width] x [height]; bo 8dp.
 */
@Composable
fun ShimmerBox(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val transition = rememberInfiniteTransition(label = "skeleton")
    val level by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = AppAnimations.pulseSpec,
        label = "skeletonLevel"
    )

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.cardHigh)
            .alpha(0.55f + 0.45f * level)
    )
}
