package com.truyencloud.shared.reader

import com.truyencloud.shared.api.CompanionChatMessage

/**
 * Ngữ cảnh và lịch sử gửi kèm một câu hỏi cho AI Companion.
 *
 * Bản cũ luôn gửi 8 đoạn ĐẦU chương (<= 2000 ký tự) dù người đọc đã ở giữa chương, và đưa câu hỏi hiện tại vào lịch sử
 * rồi gửi nó thêm một lần nữa làm "câu hỏi mới" nên máy chủ thấy câu hỏi hai lần.
 *
 * Máy chủ chỉ giữ ~2000 ký tự CUỐI của `context_text` và coi đó là phần độc giả đã đọc tới. Vì vậy cửa sổ gửi đi (~3000
 * ký tự) dồn phần lớn về PHÍA TRƯỚC chỗ đang đọc và chỉ lấn sau đó cỡ một màn hình: phần giữ lại ở máy chủ nằm quanh
 * đoạn đang đọc mà không kéo tình tiết chưa đọc vào câu trả lời.
 */
object CompanionContext {
    /** Tổng độ dài cửa sổ gửi đi. */
    const val WINDOW_CHARS = 3000

    /** Phần tối đa lấn về phía chưa đọc (≈ một màn hình chữ). */
    const val AFTER_CHARS = 1000

    /** Máy chủ chỉ dùng 6 tin gần nhất; chừa thêm một ít cho an toàn. */
    const val MAX_HISTORY = 8

    private const val SEPARATOR = "\n\n"

    val DEFAULT_SUGGESTIONS: List<String> = listOf(
        "Tóm tắt diễn biến chương này?",
        "Nhân vật chính đang toan tính điều gì?",
        "Mối đe dọa lớn nhất lúc này là ai?"
    )

    /**
     * Đoạn người đọc đang ở. Nghe TTS thì theo đoạn đang đọc; ngược lại quy từ tỉ lệ cuộn (cùng công thức khôi phục
     * vị trí: danh sách có 1 tiêu đề + các đoạn + 1 thẻ hết chương). Chỗ cần khôi phục chưa được áp dụng
     * ([restoreFraction] > 0) thì nó mới là vị trí thật, còn [scrollFraction] vẫn là của lúc trước.
     */
    fun centerIndex(
        paragraphCount: Int,
        scrollFraction: Float,
        restoreFraction: Float = 0f,
        ttsActive: Boolean = false,
        ttsParagraph: Int = 0
    ): Int {
        if (paragraphCount <= 0) return 0
        if (ttsActive && ttsParagraph in 0 until paragraphCount) return ttsParagraph
        val fraction = if (restoreFraction > 0f) restoreFraction else scrollFraction
        val item = ReaderScrollRestore.indexFor(fraction, ReaderScrollRestore.listItemCount(paragraphCount))
        return (item - 1).coerceIn(0, paragraphCount - 1)
    }

    /**
     * Cửa sổ gồm các đoạn NGUYÊN VẸN quanh [centerIndex]. Đoạn đang đọc luôn có mặt; phía sau chỉ lấy tối đa
     * [afterChars] ký tự; phần còn lại của [maxChars] dành cho phía trước (kể cả phần ngân sách phía sau không dùng
     * hết, vì máy chủ giữ phần cuối nên thêm chữ đã đọc thì vô hại).
     */
    fun window(
        paragraphs: List<String>,
        centerIndex: Int,
        maxChars: Int = WINDOW_CHARS,
        afterChars: Int = AFTER_CHARS
    ): String {
        if (paragraphs.isEmpty() || maxChars <= 0) return ""
        val center = centerIndex.coerceIn(0, paragraphs.lastIndex)
        val picked = ArrayDeque<String>()
        picked.addLast(paragraphs[center].trim().take(maxChars))
        var used = picked.first().length

        var afterUsed = 0
        var i = center + 1
        while (i < paragraphs.size) {
            val p = paragraphs[i].trim()
            i++
            if (p.isEmpty()) continue
            val cost = p.length + SEPARATOR.length
            if (afterUsed + cost > afterChars || used + cost > maxChars) break
            picked.addLast(p)
            afterUsed += cost
            used += cost
        }

        i = center - 1
        while (i >= 0) {
            val p = paragraphs[i].trim()
            i--
            if (p.isEmpty()) continue
            val cost = p.length + SEPARATOR.length
            if (used + cost > maxChars) break
            picked.addFirst(p)
            used += cost
        }
        return picked.joinToString(SEPARATOR)
    }

    /** Hai danh sách của một lượt hỏi: cái giao diện hiển thị và cái gửi lên máy chủ (KHÔNG chứa câu hỏi hiện tại). */
    data class Turn(val shown: List<CompanionChatMessage>, val request: List<CompanionChatMessage>)

    /**
     * @param history lịch sử đang hiển thị (chỉ gồm các lượt thành công, cộng câu hỏi vừa thất bại nếu có).
     * @param isRetry hỏi lại câu vừa thất bại: tin cuối của [history] đã là câu hỏi đó, không thêm lần nữa.
     */
    fun prepareTurn(history: List<CompanionChatMessage>, question: String, isRetry: Boolean = false): Turn {
        val base = if (isRetry && history.lastOrNull()?.role == ROLE_USER) history.dropLast(1) else history
        val shown = base + CompanionChatMessage(role = ROLE_USER, content = question)
        // Câu hỏi trước đó chưa có câu trả lời (lượt lỗi) chỉ là nhiễu đối với AI: không gửi lại làm lịch sử
        val answered = base.filterIndexed { i, m -> m.role != ROLE_USER || base.getOrNull(i + 1)?.role == ROLE_ASSISTANT }
        val request = answered.filter { it.content.isNotBlank() }.takeLast(MAX_HISTORY)
        return Turn(shown, request)
    }

    /**
     * Đặt tin trả lời của trợ lý ở vị trí [index] (ngay sau câu hỏi): chưa có thì thêm vào cuối, đã có (đang stream dài ra) thì thay.
     * Dùng cho cả lúc stream từng mảnh lẫn khi chốt kết quả cuối.
     */
    fun withAssistant(history: List<CompanionChatMessage>, index: Int, text: String): List<CompanionChatMessage> {
        val message = CompanionChatMessage(role = ROLE_ASSISTANT, content = text)
        return if (history.size <= index) history + message else history.toMutableList().also { it[index] = message }
    }

    /** Lượt thất bại: bỏ tin trả lời đã stream (thường là câu xin lỗi dự phòng) và mọi thứ sau nó, giữ câu hỏi của người đọc. */
    fun withoutAssistant(history: List<CompanionChatMessage>, index: Int): List<CompanionChatMessage> = history.take(index)

    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"
}
