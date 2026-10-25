package com.truyencloud.shared.tts

/**
 * Quy đổi giữa thời điểm trong tệp âm thanh của một mảnh và vị trí chữ trong mảnh đó.
 *
 * Máy chủ không trả mốc thời gian từng từ, chỉ có tổng độ dài (`X-Audio-Duration-Ms`), nên dùng mô hình TỈ LỆ có trọng số:
 * mỗi chữ cái / chữ số tốn 1 đơn vị, khoảng trắng ít hơn, dấu phẩy và dấu kết câu là chỗ giọng đọc ngừng nên tốn nhiều hơn.
 * Sai số vài trăm ms là chấp nhận được: kết quả chỉ dùng để chọn từ bắt đầu khi đổi giọng / chuyển sang giọng máy và để
 * biết đang đọc tới đoạn nào; tiếp tục bằng CÙNG giọng thì tua đúng ms đã lưu, không qua mô hình này.
 */
object TtsTextTiming {
    private const val LETTER = 1.0
    private const val SPACE = 0.5
    private const val CLAUSE_PAUSE = 3.5
    private const val SENTENCE_PAUSE = 6.0
    private const val IDEOGRAPH = 3.0
    private const val SYMBOL = 0.3

    private fun weight(text: String, i: Int): Double {
        val c = text[i]
        return when {
            c.isLowSurrogate() -> 0.0
            c.category == CharCategory.NON_SPACING_MARK -> 0.0
            TtsWordBoundary.isIdeograph(text, i) -> IDEOGRAPH
            c.isLetterOrDigit() -> LETTER
            c.isWhitespace() -> SPACE
            c in ",;:，、；：" -> CLAUSE_PAUSE
            c in ".!?…。！？" -> SENTENCE_PAUSE
            c == '—' || c == '–' -> CLAUSE_PAUSE
            else -> SYMBOL
        }
    }

    private fun total(text: String): Double {
        var sum = 0.0
        for (i in text.indices) sum += weight(text, i)
        return sum
    }

    /** Vị trí chữ (đơn vị UTF-16) đang được đọc ở [audioMs] trong tệp dài [durationMs] của chữ [text]. */
    fun charOffsetAt(text: String, audioMs: Long, durationMs: Long): Int {
        if (text.isEmpty() || durationMs <= 0L || audioMs <= 0L) return 0
        if (audioMs >= durationMs) return text.length
        val target = total(text) * audioMs.toDouble() / durationMs.toDouble()
        var acc = 0.0
        for (i in text.indices) {
            val w = weight(text, i)
            if (acc + w > target && w > 0.0) return i
            acc += w
        }
        return text.length
    }

    /** Thời điểm (ms) trong tệp dài [durationMs] mà chữ ở [charOffset] bắt đầu được đọc. */
    fun audioMsAt(text: String, charOffset: Int, durationMs: Long): Long {
        if (text.isEmpty() || durationMs <= 0L || charOffset <= 0) return 0L
        if (charOffset >= text.length) return durationMs
        val all = total(text)
        if (all <= 0.0) return 0L
        var before = 0.0
        for (i in 0 until charOffset) before += weight(text, i)
        return (durationMs.toDouble() * before / all).toLong().coerceIn(0L, durationMs)
    }

    /**
     * Độ dài ước lượng (ms) của một tệp MP3 CBR 64 kbps mà máy chủ trả (khung 192 byte = 24 ms), dùng khi thiếu
     * `X-Audio-Duration-Ms`.
     */
    fun mp3DurationMs(byteCount: Long): Long = if (byteCount <= 0L) 0L else byteCount / 192L * 24L
}
