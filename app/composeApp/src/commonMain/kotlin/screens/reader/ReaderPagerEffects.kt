package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

private const val MANUAL_TURN_HOLD_MS = 5_000L

private val pagerClockOrigin = TimeSource.Monotonic.markNow()
private fun pagerNowMs(): Long = pagerClockOrigin.elapsedNow().inWholeMilliseconds

/**
 * Ghi nhận lần lật trang THỦ CÔNG gần nhất (vuốt, chạm mép trang, nút chân trang, phím âm lượng) để bộ bám theo TTS
 * không giành lại trang ngay (tạm ngưng ~5 giây, giống chế độ cuộn).
 */
@Stable
internal class PagerManualTurnHold {
    private var holdUntilMs = 0L

    fun markManualTurn() {
        holdUntilMs = pagerNowMs() + MANUAL_TURN_HOLD_MS
    }

    fun remainingMs(): Long = holdUntilMs - pagerNowMs()
}

/**
 * Tự lật sang trang chứa đoạn TTS đang đọc. Tôn trọng cài đặt `ttsAutoScroll` ([enabled]) — bản cũ bỏ qua cài đặt này —
 * và nhường người đọc [MANUAL_TURN_HOLD_MS] sau lần lật thủ công.
 */
@Composable
internal fun TtsPagerFollowEffect(
    pagerState: PagerState,
    pages: List<BookPage>,
    enabled: Boolean,
    isTtsActive: Boolean,
    activeParagraphIndex: Int,
    hold: PagerManualTurnHold
) {
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start || interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) {
                hold.markManualTurn()
            }
        }
    }

    LaunchedEffect(activeParagraphIndex, isTtsActive, enabled, pages) {
        if (!enabled || !isTtsActive || activeParagraphIndex < 0 || pages.isEmpty()) return@LaunchedEffect
        // Đang trong thời gian nhường: chờ hết rồi mới theo (lật tay tiếp trong lúc chờ thì chờ thêm)
        while (true) {
            val remaining = hold.remainingMs()
            if (remaining <= 0L) break
            delay(remaining)
        }
        val target = pages.indexOfFirst { page -> page.chunks.any { it.paragraphIndex == activeParagraphIndex } }
        if (target >= 0 && target != pagerState.currentPage) {
            try {
                pagerState.animateScrollToPage(target)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Trang đã đổi trong lúc cuộn: lần đổi đoạn sau sẽ bám lại
            }
        }
    }
}
