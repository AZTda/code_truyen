package com.truyencloud.compose.theme

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/** Chuyển động ngắn 200-250ms, giảm tốc cuối; lò xo tắt dần hoàn toàn (không nảy). */
object AppAnimations {
    /** Lún nhẹ khi nhấn. */
    val defaultSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f)

    /** Quét của khung xương: chu kỳ 1,4 giây (không nhanh hơn 1,2 giây). */
    val pulseSpec = infiniteRepeatable<Float>(
        animation = tween(durationMillis = 1400, easing = LinearOutSlowInEasing),
        repeatMode = RepeatMode.Reverse
    )

    const val SHORT_MS = 200
    const val MEDIUM_MS = 250

    val slowTween = tween<Float>(durationMillis = 320, easing = LinearOutSlowInEasing)
}
