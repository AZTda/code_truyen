package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Cuộn tự động rảnh tay (chế độ cuộn dọc): cuộn mượt từng khung hình, tạm dừng khi người dùng chạm/kéo và
 * báo [onReachedEnd] khi tới cuối chương.
 */
@Composable
internal fun ReaderAutoScrollEffect(
    listState: LazyListState,
    enabled: Boolean,
    isPaused: Boolean,
    speed: Float,
    onReachedEnd: () -> Unit
) {
    val density = LocalDensity.current
    var isUserInteracting by remember { mutableStateOf(false) }

    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start,
                is PressInteraction.Press -> {
                    isUserInteracting = true
                }
                is DragInteraction.Stop,
                is DragInteraction.Cancel,
                is PressInteraction.Release,
                is PressInteraction.Cancel -> {
                    delay(1200L)
                    isUserInteracting = false
                }
            }
        }
    }

    LaunchedEffect(listState, enabled, isPaused, isUserInteracting, speed) {
        if (enabled && !isPaused && !isUserInteracting) {
            val basePxPerSecond = with(density) { 38.dp.toPx() }
            val frameDelay = 16L
            while (isActive) {
                delay(frameDelay)
                val step = (basePxPerSecond * speed * (frameDelay / 1000f)).coerceAtLeast(0.5f)
                val consumed = listState.scrollBy(step)
                if (consumed < step * 0.1f && !listState.canScrollForward) {
                    onReachedEnd()
                    break
                }
            }
        }
    }
}
