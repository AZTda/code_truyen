package com.truyencloud.shared.tts

/**
 * Trạng thái thuần của hẹn giờ ngủ, tách khỏi coroutine để kiểm thử được.
 *
 * [isCountingDown] = false khi phiên đọc đang tạm dừng: hẹn giờ 30 phút nghĩa là
 * 30 phút NGHE, không phải 30 phút đồng hồ kể cả lúc đã bấm dừng.
 */
data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val remainingSeconds: Int? = null,
    val isCountingDown: Boolean = false
) {
    val isTimed: Boolean get() = mode != SleepTimerMode.OFF && mode != SleepTimerMode.END_OF_CHAPTER
    val isExpired: Boolean get() = isTimed && (remainingSeconds ?: 0) <= 0
}

object SleepTimerLogic {
    /** Số giây cuối cùng dùng để giảm dần âm lượng trước khi dừng. */
    const val FADE_SECONDS = 15
    private const val MIN_FADE = 0.05f

    fun start(mode: SleepTimerMode, isPlaying: Boolean): SleepTimerState = when (mode) {
        SleepTimerMode.OFF -> SleepTimerState()
        SleepTimerMode.END_OF_CHAPTER -> SleepTimerState(mode = mode)
        else -> SleepTimerState(mode, mode.minutes * 60, isCountingDown = isPlaying)
    }

    fun tick(state: SleepTimerState): SleepTimerState {
        if (!state.isTimed || !state.isCountingDown) return state
        val next = ((state.remainingSeconds ?: 0) - 1).coerceAtLeast(0)
        return state.copy(remainingSeconds = next)
    }

    fun onPlaybackChanged(state: SleepTimerState, isPlaying: Boolean): SleepTimerState =
        if (state.isTimed) state.copy(isCountingDown = isPlaying) else state

    /** Hệ số âm lượng: 1.0 bình thường, giảm tuyến tính trong [FADE_SECONDS] giây cuối. */
    fun fadeFactor(state: SleepTimerState): Float {
        if (!state.isTimed) return 1.0f
        val remaining = state.remainingSeconds ?: return 1.0f
        if (remaining > FADE_SECONDS) return 1.0f
        return (remaining.toFloat() / FADE_SECONDS).coerceIn(MIN_FADE, 1.0f)
    }

    /**
     * Gọi khi đọc xong một chương. Trả về (trạng thái mới, có phải DỪNG hẳn không).
     * Chế độ "Hết chương" dừng phát và tự tắt; các chế độ khác để phiên đọc chuyển chương.
     */
    fun onChapterCompleted(state: SleepTimerState): Pair<SleepTimerState, Boolean> =
        if (state.mode == SleepTimerMode.END_OF_CHAPTER) SleepTimerState() to true
        else state to false

    /** Chữ thuần (không biểu tượng cảm xúc): "Tắt khi hết chương", "Tắt sau 01:05", "Tắt sau 01:05 (tạm dừng)". */
    fun label(state: SleepTimerState): String = when {
        state.mode == SleepTimerMode.END_OF_CHAPTER -> "Tắt khi hết chương"
        state.isTimed -> {
            val remaining = state.remainingSeconds ?: 0
            val m = (remaining / 60).toString().padStart(2, '0')
            val s = (remaining % 60).toString().padStart(2, '0')
            if (state.isCountingDown) "Tắt sau $m:$s" else "Tắt sau $m:$s (tạm dừng)"
        }
        else -> ""
    }
}
