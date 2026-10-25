package com.truyencloud.shared.tts

/**
 * Mảnh nào thực sự "đọc được".
 *
 * Máy chủ không có gì để tổng hợp cho mảnh chỉ gồm dấu câu / ký hiệu ("***", "——", "……", "◆◆◆", "「」"): `/v1/tts/stream`
 * trả 500, MediaPlayer báo lỗi và cầu dao hạ CẢ phiên xuống giọng máy ~3 phút dù giọng AI vẫn tốt. Vạch ngăn cảnh trong
 * truyện chính là loại mảnh này, nên người nghe bị đổi sang giọng máy giữa chương mà không hiểu vì sao.
 */
internal object SpeakableText {

    /** Có ít nhất một chữ cái hoặc chữ số (kể cả chữ Hán, chữ Việt có dấu). */
    fun isSpeakable(text: String): Boolean = text.codePoints().anyMatch { Character.isLetterOrDigit(it) }

    /** Chỉ số đầu tiên >= [from] có nội dung đọc được, hoặc -1. */
    fun nextSpeakable(texts: List<String>, from: Int): Int {
        var idx = from.coerceAtLeast(0)
        while (idx < texts.size) {
            if (isSpeakable(texts[idx])) return idx
            idx++
        }
        return -1
    }

    /** Tối đa [count] mảnh đọc được ngay sau [currentIdx]: những mảnh cần tải sẵn / tiền nạp. */
    fun ahead(texts: List<String>, currentIdx: Int, count: Int): List<Int> {
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

    /** Văn bản mảnh đọc được kế tiếp sau [idx] (gợi ý `prefetch_next`), hoặc null. */
    fun hintAfter(texts: List<String>, idx: Int): String? {
        val next = nextSpeakable(texts, idx + 1)
        return if (next >= 0) texts[next].trim() else null
    }
}
