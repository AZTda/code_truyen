package com.truyencloud.shared.reader

import com.truyencloud.shared.api.ChapterRecapResponse
import com.truyencloud.shared.api.CompanionChatResponse
import com.truyencloud.shared.api.ComprehensionQuestionItem
import com.truyencloud.shared.api.ComprehensionResponse
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.api.LoreExplainResponse
import com.truyencloud.shared.repo.ApiErrors

/**
 * Máy chủ nay GẮN CỜ mọi kết quả AI dự phòng (`is_fallback` + `error`). Ứng dụng phải coi đó là lỗi có nút thử lại,
 * không bao giờ trình bày như một câu trả lời / bản tóm tắt / định nghĩa thật — và không lưu nó vào đâu cả.
 *
 * Mọi hàm ở đây thuần (không chạm mạng hay giao diện) để kiểm thử gọn.
 */
object AiErrors {
    const val MSG_LOGIN_REQUIRED = "Đăng nhập để dùng tính năng AI này."
    const val MSG_COMPANION_FAILED = "Trợ lý AI chưa trả lời được câu này. Bạn thử lại sau ít phút nhé."

    /** Luồng trả lời đứt giữa chừng (không có sự kiện kết thúc): phần đã nhận không phải câu trả lời đầy đủ. */
    const val MSG_COMPANION_INTERRUPTED = "Kết nối bị ngắt khi trợ lý đang trả lời nên câu trả lời chưa đầy đủ. Bạn thử lại nhé."

    /** Câu báo thân thiện cho một lỗi gọi AI (401, 429, 5xx, mất mạng...). */
    fun message(error: Throwable): String {
        if (ApiErrors.isAuthError(error)) return FriendlyErrors.MSG_SESSION_EXPIRED
        // ChapterApi ném IllegalStateException("API error (503): ...") không mang mã HTTP riêng: đọc mã từ câu thông báo
        val status = ReaderTranslationFailure.statusOf(error)
        if (status != null) return FriendlyErrors.forStatus(status, null)
        return FriendlyErrors.of(error)
    }
}

// ── AI Companion ───────────────────────────────────────────────────────────────────────

sealed interface CompanionResult {
    data class Answer(val text: String, val characterName: String, val suggestions: List<String>) : CompanionResult

    /** [message] hiện trong bong bóng báo lỗi (có nút thử lại); KHÔNG đưa vào lịch sử trò chuyện. */
    data class Failed(val message: String) : CompanionResult
}

object CompanionPolicy {
    fun classify(res: CompanionChatResponse): CompanionResult {
        val reply = res.reply.trim()
        if (res.is_fallback) return CompanionResult.Failed(reply.ifBlank { AiErrors.MSG_COMPANION_FAILED })
        // Nửa câu trả lời của một luồng bị đứt không được lưu như câu trả lời thật (cũng không gửi lại cho AI làm lịch sử)
        if (res.is_incomplete) return CompanionResult.Failed(AiErrors.MSG_COMPANION_INTERRUPTED)
        if (reply.isEmpty()) return CompanionResult.Failed(AiErrors.MSG_COMPANION_FAILED)
        return CompanionResult.Answer(reply, res.character_name, res.suggested_questions.filter { it.isNotBlank() })
    }
}

// ── Tóm tắt chương ("Previously on") ───────────────────────────────────────────────────

sealed interface RecapOutcome {
    data class Ready(val recap: ChapterRecapResponse) : RecapOutcome

    /** Chương đầu / chưa có chương trước nào được tóm tắt: không có gì để nhắc lại (KHÔNG phải lỗi). */
    data object NothingToRecap : RecapOutcome

    data class Failed(val reason: String) : RecapOutcome
}

object RecapPolicy {
    const val TITLE_FAILED = "Chưa tóm tắt được"
    const val NOTHING_TO_RECAP = "Đây là chương đầu tiên nên chưa có phần trước để nhắc lại."
    private const val DEFAULT_REASON = "AI chưa tóm tắt được các chương trước. Vui lòng thử lại sau."

    /** Chương 1 không có chương trước: khỏi hỏi máy chủ (nó chỉ trả nội dung tĩnh gắn cờ dự phòng). */
    fun needsRequest(chapterNumber: Int): Boolean = chapterNumber > 1

    fun outcome(chapterNumber: Int, recap: ChapterRecapResponse): RecapOutcome {
        if (!needsRequest(chapterNumber)) return RecapOutcome.NothingToRecap
        if (recap.is_fallback) {
            // Không kèm lỗi = nội dung tĩnh của chương mở đầu; có lỗi = AI hỏng / chưa có chương trước
            val reason = recap.error?.trim().orEmpty()
            return if (reason.isEmpty()) RecapOutcome.NothingToRecap else RecapOutcome.Failed(reason)
        }
        if (recap.chapters_covered.isEmpty()) return RecapOutcome.NothingToRecap
        if (recap.bullets.isEmpty() && recap.context.isBlank()) return RecapOutcome.Failed(DEFAULT_REASON)
        return RecapOutcome.Ready(recap)
    }
}

// ── Tra cứu Hán Việt / điển tích ───────────────────────────────────────────────────────

sealed interface LoreOutcome {
    data class Ready(val lore: LoreExplainResponse) : LoreOutcome
    data class Failed(val message: String) : LoreOutcome
}

object LorePolicy {
    const val MSG_FAILED = "Chưa giải nghĩa được thuật ngữ này. Vui lòng thử lại."

    /** Kết quả dự phòng hoặc không có nghĩa thì KHÔNG được hiện như định nghĩa và càng không được lưu vào trí nhớ thuật ngữ. */
    fun outcome(res: LoreExplainResponse): LoreOutcome {
        if (res.is_fallback || res.definition.isBlank()) {
            return LoreOutcome.Failed(res.error?.trim()?.takeIf { it.isNotEmpty() } ?: MSG_FAILED)
        }
        return LoreOutcome.Ready(res)
    }
}

// ── Trắc nghiệm đọc hiểu ───────────────────────────────────────────────────────────────

sealed interface QuizOutcome {
    data class Ready(val questions: List<ComprehensionQuestionItem>) : QuizOutcome
    data class Failed(val message: String) : QuizOutcome
}

object ComprehensionPolicy {
    const val MSG_FALLBACK = "AI chưa tạo được câu hỏi cho chương này."
    const val MIN_QUESTIONS = 2

    /**
     * Bộ câu hỏi mẫu của máy chủ (`is_fallback`) là câu chung chung, đáp án luôn dễ đoán — không phải bài kiểm tra về chương
     * này nên không hiện và không lưu (ở đâu cũng vậy).
     */
    fun outcome(res: ComprehensionResponse): QuizOutcome {
        if (res.isFallback) return QuizOutcome.Failed(MSG_FALLBACK)
        val questions = normalize(res.questions)
        if (questions.size < MIN_QUESTIONS) return QuizOutcome.Failed(MSG_FALLBACK)
        return QuizOutcome.Ready(questions)
    }

    /**
     * Bỏ câu thiếu đáp án / chỉ số đúng sai và đánh lại mã khi trống hoặc trùng: câu trả lời được lưu theo mã câu hỏi nên
     * hai câu cùng mã (hay cùng mã rỗng) sẽ dùng chung một đáp án.
     */
    fun normalize(questions: List<ComprehensionQuestionItem>): List<ComprehensionQuestionItem> {
        val valid = questions.filter {
            it.question.isNotBlank() && it.options.size >= 2 && it.correctIndex in it.options.indices
        }
        val seen = HashSet<String>()
        return valid.mapIndexed { index, q ->
            var id = q.id.trim()
            if (id.isEmpty() || !seen.add(id)) {
                id = "q${index + 1}"
                var n = index + 1
                while (!seen.add(id)) id = "q${++n}"
            }
            if (id == q.id) q else q.copy(id = id)
        }
    }
}
