package com.truyencloud.shared.tts

/**
 * Tìm ĐẦU TỪ để đọc tiếp giữa một mảnh bằng giọng máy (đọc lại phần chữ từ từ đang đọc dở).
 *
 * Chỉ số là đơn vị UTF-16 như `String` của Kotlin (cũng là đơn vị của `onRangeStart` trên Android và NSRange trên iOS).
 * Không bao giờ cắt giữa cặp surrogate (emoji, chữ Hán hiếm) hay tách dấu tổ hợp khỏi chữ gốc (tiếng Việt dạng NFD).
 * Chữ Hán / kana: mỗi ký tự là một "từ".
 */
object TtsWordBoundary {

    /**
     * Đầu của từ chứa [offset]. Đứng giữa khoảng trắng / dấu câu (từ trước đã đọc xong) thì là đầu từ KẾ TIẾP; không còn từ
     * nào phía sau thì trả `text.length`.
     */
    fun wordStart(text: String, offset: Int): Int {
        if (text.isEmpty()) return 0
        var i = offset.coerceIn(0, text.length)
        if (i >= text.length) return text.length
        // Về ký tự gốc: không đứng ở nửa sau của cặp surrogate hay ở dấu tổ hợp
        while (i > 0 && (text[i].isLowSurrogate() || isCombining(text[i]))) i--
        if (!isWordUnit(text, i)) return nextWordStart(text, i)
        if (isIdeograph(text, i)) return i
        while (i > 0) {
            val prev = baseIndexBefore(text, i)
            if (!isWordUnit(text, prev) || isIdeograph(text, prev)) break
            i = prev
        }
        return i
    }

    /** Đầu của từ CUỐI CÙNG trong [text] (0 nếu không có từ nào). */
    fun lastWordStart(text: String): Int {
        var i = text.length - 1
        while (i >= 0 && (text[i].isLowSurrogate() || isCombining(text[i]) || !isWordUnit(text, i))) i--
        return if (i < 0) 0 else wordStart(text, i)
    }

    /** Phần chữ cần đọc lại bắt đầu từ đầu từ của [offset], kèm vị trí bắt đầu đó trong [text]. */
    fun sliceFromWord(text: String, offset: Int): Pair<Int, String> {
        val start = wordStart(text, offset)
        return start to text.substring(start)
    }

    private fun nextWordStart(text: String, from: Int): Int {
        var i = from
        while (i < text.length && !isWordUnit(text, i)) i++
        return i
    }

    /** Chỉ số ký tự gốc ngay trước [i] (bỏ qua dấu tổ hợp và nửa sau surrogate). */
    private fun baseIndexBefore(text: String, i: Int): Int {
        var j = i - 1
        while (j > 0 && (text[j].isLowSurrogate() || isCombining(text[j]))) j--
        return j
    }

    private fun isCombining(c: Char): Boolean =
        c.category == CharCategory.NON_SPACING_MARK || c.category == CharCategory.COMBINING_SPACING_MARK ||
            c.category == CharCategory.ENCLOSING_MARK

    /** Ký tự (gốc) ở [i] có thuộc một từ không: chữ, số, dấu nháy giữa từ, hoặc chữ ngoài mặt phẳng cơ bản. */
    private fun isWordUnit(text: String, i: Int): Boolean {
        val c = text[i]
        if (c.isHighSurrogate()) return isIdeograph(text, i) || isLetterLikeSupplementary(c)
        return c.isLetterOrDigit() || isCombining(c) || (c == '\'' || c == '’') && i > 0 && text[i - 1].isLetterOrDigit()
    }

    /** Emoji (U+1F000 trở lên) không phải chữ; các mặt phẳng phụ còn lại (chữ hiếm) coi như chữ. */
    private fun isLetterLikeSupplementary(high: Char): Boolean = high < '\uD83C' || high > '\uD83E'

    fun isIdeograph(text: String, i: Int): Boolean {
        val c = text[i]
        return c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿' ||
            c in '぀'..'ヿ' || c in '\uD840'..'\uD87F'
    }
}
