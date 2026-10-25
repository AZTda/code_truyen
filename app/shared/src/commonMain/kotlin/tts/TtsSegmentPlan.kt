package com.truyencloud.shared.tts

/**
 * Phép tính chỉ số mảnh cho bộ phát online: mảnh kế tiếp có chữ, và những mảnh
 * cần tải sẵn phía trước. Tách riêng để kiểm thử — lỗi cũ là so `nextPreparedIndex`
 * với chính nó + 1 nên không bao giờ dùng được bộ phát đã tải sẵn.
 */
object TtsSegmentPlan {
    /** Số mảnh tải sẵn phía trước mảnh đang phát. */
    const val BUFFER_AHEAD = 2

    /**
     * Mảnh có gì để đọc không: phải có ít nhất một chữ cái hoặc chữ số. Dòng ngăn cảnh như "***", "......", "———", "◆◆◆"
     * không có âm nào: máy chủ báo lỗi "không có âm thanh", bộ đọc coi là giọng online hỏng, bật cầu dao và cả chương còn lại
     * bị đọc bằng giọng máy ~3 phút.
     */
    fun isSpeakable(text: String): Boolean = text.any { it.isLetterOrDigit() || it in CJK_EXTENSION_HIGH_SURROGATES }

    /** Chữ Hán hiếm (mặt phẳng phụ U+20000..2FFFF) là cặp surrogate: từng đơn vị không "là chữ" nhưng mảnh có nội dung thật. */
    private val CJK_EXTENSION_HIGH_SURROGATES = '\uD840'..'\uD87F'

    /** Chỉ số đầu tiên >= [from] có nội dung đọc được, hoặc -1 nếu hết chương. */
    fun nextSpeakable(texts: List<String>, from: Int): Int {
        var idx = from.coerceAtLeast(0)
        while (idx < texts.size) {
            if (isSpeakable(texts[idx])) return idx
            idx++
        }
        return -1
    }

    /** Các mảnh (tối đa [count]) nên được tải sẵn sau [currentIdx]. */
    fun bufferAhead(texts: List<String>, currentIdx: Int, count: Int = BUFFER_AHEAD): List<Int> {
        val result = mutableListOf<Int>()
        var cursor = currentIdx + 1
        while (result.size < count) {
            val next = nextSpeakable(texts, cursor)
            if (next < 0) break
            result += next
            cursor = next + 1
        }
        return result
    }

    /** Văn bản mảnh ngay sau [idx] — gợi ý `prefetch_next` cho máy chủ (mảnh tiếng Trung không bao giờ gửi lên máy chủ). */
    fun prefetchHint(texts: List<String>, idx: Int): String? {
        val next = nextSpeakable(texts, idx + 1)
        return if (next >= 0 && !isDeviceOnly(texts[next])) texts[next].trim() else null
    }

    /**
     * Mảnh mà giọng AI (VieNeu) không đọc được: tiếng Trung. Đọc THẲNG bằng giọng của điện thoại, không gọi máy chủ (máy chủ
     * trả 422) và không đụng cầu dao — đoạn sau vẫn đọc bằng giọng AI.
     */
    fun isDeviceOnly(text: String): Boolean = TtsTextLanguage.langParam(text.trim()) == "zh"

    /** Các mảnh (tối đa [count]) sau [currentIdx] cần tải sẵn âm thanh từ máy chủ: đọc được và không phải mảnh giọng máy. */
    fun serverAhead(texts: List<String>, currentIdx: Int, count: Int = BUFFER_AHEAD): List<Int> {
        val result = mutableListOf<Int>()
        var cursor = currentIdx + 1
        while (result.size < count) {
            val next = nextSpeakable(texts, cursor)
            if (next < 0) break
            if (!isDeviceOnly(texts[next])) result += next
            cursor = next + 1
        }
        return result
    }
}
