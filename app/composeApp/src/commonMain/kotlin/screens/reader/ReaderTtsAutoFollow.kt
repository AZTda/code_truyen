package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

private const val USER_SCROLL_HOLD_MS = 5_000L

private val clockOrigin = TimeSource.Monotonic.markNow()
private fun nowMs(): Long = clockOrigin.elapsedNow().inWholeMilliseconds

/**
 * Cuộn danh sách theo đoạn TTS đang đọc.
 *
 * - Tôn trọng cài đặt `ttsAutoScroll` ([enabled]); trước đây cài đặt này được lưu nhưng không
 *   được đọc ở đâu cả.
 * - Người dùng đang tự cuộn thì NGỪNG bám theo ~5 giây kể từ lần chạm/thả cuối, nếu không
 *   danh sách giật ngược về đoạn đang đọc ngay giữa lúc họ kéo.
 */
@Composable
internal fun TtsAutoFollowEffect(
    listState: LazyListState,
    enabled: Boolean,
    isTtsActive: Boolean,
    activeParagraphIndex: Int,
    paragraphCount: Int
) {
    var holdUntilMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start || interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) {
                holdUntilMs = nowMs() + USER_SCROLL_HOLD_MS
            }
        }
    }

    LaunchedEffect(activeParagraphIndex, isTtsActive, enabled, paragraphCount) {
        if (!enabled || !isTtsActive || activeParagraphIndex !in 0 until paragraphCount) return@LaunchedEffect
        // Chờ hết thời gian tạm ngưng; người dùng cuộn tiếp trong lúc chờ thì chờ thêm
        while (true) {
            val remaining = holdUntilMs - nowMs()
            if (remaining <= 0L) break
            delay(remaining)
        }
        // Mục 0 là tiêu đề chương nên đoạn i nằm ở mục i + 1
        listState.animateScrollToItem(activeParagraphIndex + 1)
    }
}
