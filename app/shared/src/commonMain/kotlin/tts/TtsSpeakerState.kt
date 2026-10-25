package com.truyencloud.shared.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Các trạng thái công khai của một phiên đọc ([BaseTtsSpeaker]) và vị trí đang đọc tới. Tách riêng để [BaseTtsSpeaker] chỉ
 * còn luật chuyển trạng thái.
 *
 * Vị trí ([playbackPosition]) do bộ phát của nền tảng báo về qua [onSegmentProgress]: giọng AI ~4 lần/giây (ms âm thanh quy
 * ra chữ bằng [TtsTextTiming]), giọng máy theo từng từ (`onRangeStart` / `willSpeakRangeOfSpeechString`).
 */
abstract class TtsSpeakerState : TtsPlatformHooks() {

    protected var texts: List<String> = emptyList()
        private set

    protected val isPlayingState = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = isPlayingState.asStateFlow()
    protected val currentIndexState = MutableStateFlow(0)
    override val currentParagraphIndex: StateFlow<Int> = currentIndexState.asStateFlow()
    protected val speedState = MutableStateFlow(1.0f)
    override val speed: StateFlow<Float> = speedState.asStateFlow()
    protected val backgroundState = MutableStateFlow(true)
    override val backgroundPlayback: StateFlow<Boolean> = backgroundState.asStateFlow()
    protected val profileState = MutableStateFlow(TtsVoiceCatalog.defaultProfile())
    override val currentVoiceProfile: StateFlow<TtsVoiceProfile> = profileState.asStateFlow()
    protected val onlineState = MutableStateFlow(true)
    override val isOnlineMode: StateFlow<Boolean> = onlineState.asStateFlow()
    protected val errorState = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = errorState.asStateFlow()

    private val positionState = MutableStateFlow(TtsPlaybackPosition())
    override val playbackPosition: StateFlow<TtsPlaybackPosition> = positionState.asStateFlow()
    private val deviceFallbackState = MutableStateFlow(false)
    override val isDeviceVoiceFallback: StateFlow<Boolean> = deviceFallbackState.asStateFlow()

    /** Vị trí hiện tại (để bộ phát biết bắt đầu mảnh từ đâu). */
    protected val position: TtsPlaybackPosition get() = positionState.value

    protected fun replaceTexts(paragraphs: List<String>) {
        texts = paragraphs
    }

    /** Đặt vị trí khi phiên tự đổi mảnh (bắt đầu, sang mảnh kế, người dùng nhảy đoạn): luôn là ĐẦU mảnh. */
    protected fun moveToSegmentStart(index: Int) {
        currentIndexState.value = index
        positionState.value = TtsPlaybackPosition.startOf(index)
    }

    /** Đặt vị trí bắt đầu của một phiên mới (có thể giữa mảnh: nghe tiếp đúng chữ đã lưu). */
    protected fun setStartPosition(start: TtsPlaybackPosition) {
        val idx = start.segmentIndex.coerceIn(0, (texts.size - 1).coerceAtLeast(0))
        currentIndexState.value = idx
        positionState.value = if (idx == start.segmentIndex) start else TtsPlaybackPosition.startOf(idx)
    }

    /** Mảnh bỏ qua được (dòng ngăn cảnh...) khiến chỉ số nhảy: vị trí giữa mảnh cũ không còn ý nghĩa. */
    protected fun alignPositionTo(index: Int) {
        if (positionState.value.segmentIndex != index) positionState.value = TtsPlaybackPosition.startOf(index)
        if (currentIndexState.value != index) currentIndexState.value = index
    }

    /**
     * Bộ phát báo đang đọc tới [charOffset] (đơn vị UTF-16 trong chữ đã `trim()` của mảnh) của mảnh [index]; [audioMs] là ms
     * trong tệp của giọng AI [voiceKey] (-1 với giọng máy). Báo của mảnh cũ (đã bị thay) bị bỏ qua.
     */
    protected fun onSegmentProgress(index: Int, charOffset: Int, audioMs: Long = -1L, voiceKey: String? = null) {
        if (index != currentIndexState.value || index !in texts.indices) return
        val clamped = charOffset.coerceIn(0, texts[index].trim().length)
        positionState.value = TtsPlaybackPosition(index, clamped, if (voiceKey == null) -1L else audioMs, voiceKey)
    }

    /** Nền tảng báo đang đọc bằng giọng của điện thoại thay cho giọng AI (máy chủ lỗi), hoặc đã quay lại giọng AI. */
    protected fun setDeviceVoiceFallback(active: Boolean) {
        deviceFallbackState.value = active
    }
}
