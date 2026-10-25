@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.truyencloud.shared.tts

import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance

/**
 * Đọc truyện bằng giọng máy (AVSpeechSynthesizer). Bộ tổng hợp này CHỈ dùng cho phiên đọc;
 * đọc từ và nghe thử có bộ tổng hợp riêng. Mỗi câu nhớ "utterance hiện hành" để sự kiện
 * hoàn tất của câu cũ (đã bị thay) không làm truyện nhảy đoạn.
 *
 * Nghe tiếp giữa mảnh: chỉ đọc phần chữ từ ĐẦU TỪ đang đọc dở ([TtsSegmentStart.charOffset]); vị trí từ
 * `willSpeakRangeOfSpeechString` được cộng lại [base] để luôn là vị trí trong chữ của CẢ mảnh.
 */
internal class IosOfflineSpeech(
    private val onStarted: () -> Unit,
    private val onFinished: () -> Unit,
    private val onNoVietnameseVoice: () -> Unit,
    private val onWord: (charOffset: Int) -> Unit = {}
) {
    private val synthesizer = AVSpeechSynthesizer()
    private val delegate = IosSynthesizerDelegate()
    private var current: AVSpeechUtterance? = null
    private var base = 0

    init {
        synthesizer.delegate = delegate
        delegate.onUtteranceStarted = { u -> if (u === current) onStarted() }
        delegate.onUtteranceFinished = { u ->
            if (u === current) {
                current = null
                onFinished()
            }
        }
        delegate.onWillSpeakRange = { u, location -> if (u === current) onWord(base + location) }
    }

    /**
     * Đọc [text] (chữ đã `trim()` của cả mảnh) từ vị trí [from]. Ngôn ngữ và giọng chọn theo CẢ mảnh, không theo phần đuôi.
     * Máy chưa có giọng tiếng Việt cho đoạn tiếng Việt: không phát gì mà báo [onNoVietnameseVoice].
     */
    fun speak(text: String, profile: TtsVoiceProfile, speed: Float, volume: Float, from: Int = 0) {
        stop()
        base = TtsWordBoundary.wordStart(text, from.coerceIn(0, text.length)).let { if (it >= text.length) 0 else it }
        val part = text.substring(base)
        val utterance = IosTtsVoiceMatcher.buildUtterance(part, profile, speed, volume, languageOf = text)
            ?: return onNoVietnameseVoice()
        current = utterance
        synthesizer.speakUtterance(utterance)
    }

    fun pause() {
        if (current != null && synthesizer.isSpeaking()) {
            synthesizer.pauseSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
    }

    /** Tiếp tục đúng chỗ đã dừng; false nếu không có câu nào đang tạm dừng. */
    fun resume(): Boolean = current != null && synthesizer.isPaused() && synthesizer.continueSpeaking()

    fun stop() {
        current = null
        if (synthesizer.isSpeaking() || synthesizer.isPaused()) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
    }
}
