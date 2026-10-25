package com.truyencloud.compose.screens.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.ProgressBar3
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.LocalCustomColors

/** Huy hiệu phần trăm độ sáng khi vuốt dọc mép trái: thẻ phẳng cardHigh đặc + đường kẻ mảnh, thanh tiến độ 3dp màu nhấn. */
@Composable
fun ReaderBrightnessHud(
    visible: Boolean,
    brightness: Float,
    percent: Int,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) + scaleIn(initialScale = 0.92f),
        exit = fadeOut(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) + scaleOut(targetScale = 0.92f),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(CardShape)
                .background(colors.cardHigh)
                .border(1.dp, colors.hairline, CardShape)
                .padding(horizontal = 24.dp, vertical = 18.dp)
        ) {
            Icon(
                imageVector = TruyenCloudIcons.Sun,
                contentDescription = "Độ sáng màn hình",
                tint = colors.textPrimary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            ProgressBar3(
                progress = ((brightness - 0.15f) / 0.85f).coerceIn(0.04f, 1f),
                modifier = Modifier.width(112.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "$percent%", style = AppText.bodyStrong, color = colors.textPrimary)
        }
    }
}
