package com.truyencloud.shared.tts

/**
 * Chỗ đang đọc trong một chương: mảnh TTS, ký tự trong chữ của mảnh đó, và (giọng AI) ms trong tệp âm thanh.
 *
 * [audioMs] chỉ đúng với tệp của giọng [audioVoiceKey] cho đúng chữ của mảnh: tiếp tục bằng cùng giọng thì tua tới đó (lùi
 * [RESUME_BACKOFF_MS] cho khỏi mất đầu chữ); đổi giọng hay chuyển sang giọng máy thì quy đổi theo [charOffset] về đầu từ.
 */
data class TtsPlaybackPosition(
    val segmentIndex: Int = 0,
    val charOffset: Int = 0,
    val audioMs: Long = -1L,
    val audioVoiceKey: String? = null
) {
    /** Có phải đầu mảnh không (không có gì để "nghe tiếp giữa chừng"). */
    val atSegmentStart: Boolean get() = charOffset <= 0 && audioMs <= 0L

    /** ms để tua tới trong tệp của giọng [voiceKey], hoặc -1 nếu vị trí này không thuộc tệp đó. */
    fun audioMsFor(voiceKey: String?): Long =
        if (audioMs > 0L && voiceKey != null && voiceKey == audioVoiceKey) (audioMs - RESUME_BACKOFF_MS).coerceAtLeast(0L) else -1L

    companion object {
        /** Lùi lại một chút khi tiếp tục giữa chừng để không mất âm đầu của chữ đang đọc dở. */
        const val RESUME_BACKOFF_MS = 300L

        fun startOf(segmentIndex: Int) = TtsPlaybackPosition(segmentIndex = segmentIndex.coerceAtLeast(0))
    }
}

/**
 * Điểm bắt đầu phát một mảnh mà bộ phát của từng nền tảng nhận ([TtsPlatformHooks.playSegment]).
 * [charOffset] đã được đưa về ĐẦU TỪ ([TtsWordBoundary]); [audioMs] >= 0 nghĩa là tua đúng tới đó trong tệp của giọng [voiceKey].
 */
data class TtsSegmentStart(
    val charOffset: Int = 0,
    val audioMs: Long = -1L,
    val voiceKey: String? = null
) {
    val isFromBeginning: Boolean get() = charOffset <= 0 && audioMs <= 0L

    companion object {
        val BEGINNING = TtsSegmentStart()

        /** Điểm bắt đầu cho [position] trên chữ [text] của mảnh, khi bộ phát sẽ dùng giọng [voiceKey]. */
        fun from(position: TtsPlaybackPosition, text: String, voiceKey: String?): TtsSegmentStart {
            if (position.atSegmentStart) return BEGINNING
            var word = TtsWordBoundary.wordStart(text, position.charOffset)
            // Dừng sau từ cuối của mảnh: đọc lại từ cuối, không đọc lại cả mảnh
            if (word >= text.length) word = TtsWordBoundary.lastWordStart(text)
            val audio = position.audioMsFor(voiceKey)
            if (word <= 0 && audio <= 0L) return BEGINNING
            return TtsSegmentStart(charOffset = word, audioMs = audio, voiceKey = voiceKey)
        }
    }
}
