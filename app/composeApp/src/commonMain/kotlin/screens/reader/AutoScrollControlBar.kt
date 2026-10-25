package com.truyencloud.compose.screens.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Thanh điều khiển cuộn tự động: thẻ phẳng nổi ở đáy màn hình (nền cardHigh đặc + đường kẻ mảnh), gồm tạm dừng / tiếp tục, trạng thái,
 * tốc độ và thoát. [respectNavigationBar] = false khi thanh dưới của màn đọc đang hiện bên dưới (thanh đó đã chừa vùng điều hướng).
 */
@Composable
fun AutoScrollControlBar(
    visible: Boolean,
    isPaused: Boolean,
    speed: Float,
    onTogglePause: () -> Unit,
    onCycleSpeed: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    respectNavigationBar: Boolean = true
) {
    val colors = LocalCustomColors.current

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) +
            slideInVertically(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) { it / 2 },
        exit = fadeOut(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) +
            slideOutVertically(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) { it / 2 },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (respectNavigationBar) Modifier.navigationBarsPadding() else Modifier)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(CardShape)
                    .background(colors.cardHigh)
                    .border(1.dp, colors.hairline, CardShape)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuietIconButton(
                    icon = if (isPaused) TruyenCloudIcons.PlayArrow else TruyenCloudIcons.Pause,
                    contentDescription = if (isPaused) "Tiếp tục cuộn" else "Tạm dừng cuộn",
                    onClick = onTogglePause,
                    tint = colors.textPrimary
                )
                Text(
                    text = if (isPaused) "Tạm dừng" else "Đang cuộn",
                    style = AppText.secondaryStrong,
                    color = if (isPaused) colors.textSecondary else colors.textPrimary
                )
                TextActionButton(
                    text = "${speed}x",
                    onClick = onCycleSpeed,
                    modifier = Modifier.semantics { contentDescription = "Tốc độ cuộn ${speed}x, chạm để đổi" }
                )
                QuietIconButton(TruyenCloudIcons.Close, "Thoát cuộn tự động", onClose)
            }
        }
    }
}
