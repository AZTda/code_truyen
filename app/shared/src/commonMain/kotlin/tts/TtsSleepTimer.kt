package com.truyencloud.shared.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bộ đếm hẹn giờ ngủ dùng chung cho Android và iOS.
 *
 * Chỉ đếm khi đang phát ([onPlaybackChanged]); [onFade] nhận hệ số âm lượng để
 * nền tảng áp vào MỌI bộ phát (giọng online, giọng máy, âm thanh nền).
 */
class TtsSleepTimer(
    private val scope: CoroutineScope,
    private val onFade: (Float) -> Unit,
    private val onExpired: () -> Unit,
    private val onLabelChanged: (String) -> Unit = {}
) {
    private var state = SleepTimerState()
    private var job: Job? = null
    private var lastFactor = 1.0f

    private val _mode = MutableStateFlow(SleepTimerMode.OFF)
    val mode: StateFlow<SleepTimerMode> = _mode.asStateFlow()

    private val _remainingSeconds = MutableStateFlow<Int?>(null)
    val remainingSeconds: StateFlow<Int?> = _remainingSeconds.asStateFlow()

    fun set(mode: SleepTimerMode, isPlaying: Boolean) {
        apply(SleepTimerLogic.start(mode, isPlaying))
        emitFade(1.0f)
    }

    fun onPlaybackChanged(isPlaying: Boolean) {
        if (state.isTimed && state.isCountingDown != isPlaying) {
            apply(SleepTimerLogic.onPlaybackChanged(state, isPlaying))
        }
    }

    /** true nếu đang hẹn "Hết chương": hẹn giờ tự tắt và người gọi phải DỪNG phát. */
    fun consumeChapterEnd(): Boolean {
        val (next, shouldStop) = SleepTimerLogic.onChapterCompleted(state)
        if (shouldStop) {
            apply(next)
            emitFade(1.0f)
        }
        return shouldStop
    }

    fun cancel() {
        apply(SleepTimerState())
        emitFade(1.0f)
    }

    private fun apply(next: SleepTimerState) {
        state = next
        publish()
        if (next.isTimed && next.isCountingDown) {
            ensureTicking()
        } else {
            job?.cancel()
            job = null
        }
    }

    private fun publish() {
        _mode.value = state.mode
        _remainingSeconds.value = if (state.isTimed) state.remainingSeconds else null
        onLabelChanged(SleepTimerLogic.label(state))
    }

    private fun emitFade(factor: Float) {
        if (factor == lastFactor) return
        lastFactor = factor
        onFade(factor)
    }

    private fun ensureTicking() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(1000L)
                state = SleepTimerLogic.tick(state)
                publish()
                emitFade(SleepTimerLogic.fadeFactor(state))
                if (state.isExpired) {
                    state = SleepTimerState()
                    publish()
                    job = null
                    // Dừng trước rồi mới trả âm lượng về 1.0, tránh "bụp" to lên một nhịp
                    onExpired()
                    emitFade(1.0f)
                    break
                }
            }
        }
    }
}
