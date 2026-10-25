package com.truyencloud.shared.reader

import com.truyencloud.shared.api.ApiHttpException
import com.truyencloud.shared.api.ChapterRecapResponse
import com.truyencloud.shared.api.CompanionChatResponse
import com.truyencloud.shared.api.ComprehensionQuestionItem
import com.truyencloud.shared.api.ComprehensionResponse
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.api.LoreExplainResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AiResultPolicyTest {

    // ── AI Companion ────────────────────────────────────────────────────────

    @Test
    fun companionFallbackIsAFailureNeverAnAnswer() {
        val res = CompanionChatResponse(reply = "Xin lỗi, trợ lý AI tạm thời chưa trả lời được.", is_fallback = true, error = "AI không trả lời hợp lệ.")
        val out = CompanionPolicy.classify(res)
        assertIs<CompanionResult.Failed>(out)
        assertEquals("Xin lỗi, trợ lý AI tạm thời chưa trả lời được.", out.message)
    }

    @Test
    fun companionFallbackWithoutTextUsesTheFriendlyMessage() {
        val out = CompanionPolicy.classify(CompanionChatResponse(reply = "  ", is_fallback = true))
        assertEquals(AiErrors.MSG_COMPANION_FAILED, (out as CompanionResult.Failed).message)
    }

    @Test
    fun companionBlankReplyIsAFailureEvenWithoutTheFlag() {
        assertIs<CompanionResult.Failed>(CompanionPolicy.classify(CompanionChatResponse(reply = "")))
    }

    @Test
    fun companionStreamCutMidAnswerIsAFailureWithRetryNotAnAnswer() {
        // Lỗi gốc: phần đã nhận của luồng bị đứt được trình bày như một câu trả lời hoàn chỉnh và còn gửi lại cho AI làm lịch sử
        val out = CompanionPolicy.classify(CompanionChatResponse(reply = "Nửa câu trả lời bị cắt", is_incomplete = true))
        assertIs<CompanionResult.Failed>(out)
        assertEquals(AiErrors.MSG_COMPANION_INTERRUPTED, out.message)
        assertTrue("Nửa câu" !in out.message, "phần chữ dở dang không được thành nội dung bong bóng lỗi")
    }

    @Test
    fun companionFallbackKeepsItsServerApologyEvenIfIncompleteFlagIsAbsent() {
        val out = CompanionPolicy.classify(CompanionChatResponse(reply = "Xin lỗi.", is_fallback = true))
        assertEquals("Xin lỗi.", (out as CompanionResult.Failed).message)
    }

    @Test
    fun companionAnswerKeepsServerSuggestionsAndDropsBlankOnes() {
        val out = CompanionPolicy.classify(
            CompanionChatResponse(reply = " Câu trả lời ", character_name = "Sư phụ", suggested_questions = listOf("Hỏi gì tiếp?", " "))
        )
        assertIs<CompanionResult.Answer>(out)
        assertEquals("Câu trả lời", out.text)
        assertEquals("Sư phụ", out.characterName)
        assertEquals(listOf("Hỏi gì tiếp?"), out.suggestions)
    }

    // ── Tóm tắt chương ──────────────────────────────────────────────────────

    private fun recap(
        fallback: Boolean = false,
        error: String? = null,
        covered: List<Int> = listOf(8, 9),
        bullets: List<String> = listOf("Sự kiện A"),
        context: String = "Bối cảnh"
    ) = ChapterRecapResponse(
        novel_id = "n", chapter_number = 10, bullets = bullets, context = context,
        is_fallback = fallback, error = error, chapters_covered = covered
    )

    @Test
    fun recapIsSkippedForTheFirstChapterEvenIfTheServerSentContent() {
        assertEquals(false, RecapPolicy.needsRequest(1))
        assertEquals(true, RecapPolicy.needsRequest(2))
        assertEquals(RecapOutcome.NothingToRecap, RecapPolicy.outcome(1, recap()))
        assertEquals(RecapOutcome.NothingToRecap, RecapPolicy.outcome(0, recap()))
    }

    @Test
    fun recapStaticFallbackWithoutErrorIsHidden() {
        assertEquals(RecapOutcome.NothingToRecap, RecapPolicy.outcome(5, recap(fallback = true, covered = emptyList())))
    }

    @Test
    fun recapFallbackWithAnErrorIsAFailureWithTheServerReason() {
        val out = RecapPolicy.outcome(5, recap(fallback = true, error = "AI chưa tóm tắt được các chương trước.", covered = emptyList(), bullets = emptyList(), context = ""))
        assertEquals(RecapOutcome.Failed("AI chưa tóm tắt được các chương trước."), out)
    }

    @Test
    fun recapWithNoCoveredChaptersIsHidden() {
        assertEquals(RecapOutcome.NothingToRecap, RecapPolicy.outcome(5, recap(covered = emptyList())))
    }

    @Test
    fun recapRealContentIsShown() {
        val r = recap()
        assertEquals(RecapOutcome.Ready(r), RecapPolicy.outcome(10, r))
    }

    @Test
    fun recapCoveredButEmptyIsAFailureNotABlankCard() {
        assertIs<RecapOutcome.Failed>(RecapPolicy.outcome(10, recap(bullets = emptyList(), context = " ")))
    }

    // ── Tra cứu điển tích ───────────────────────────────────────────────────

    @Test
    fun loreFallbackIsAFailureWithTheServerReason() {
        val out = LorePolicy.outcome(LoreExplainResponse(term = "筑基", is_fallback = true, error = "Không giải nghĩa được thuật ngữ này."))
        assertEquals(LoreOutcome.Failed("Không giải nghĩa được thuật ngữ này."), out)
    }

    @Test
    fun loreWithoutDefinitionIsAFailureEvenIfNotFlagged() {
        assertEquals(LoreOutcome.Failed(LorePolicy.MSG_FAILED), LorePolicy.outcome(LoreExplainResponse(term = "x", definition = " ")))
    }

    @Test
    fun loreRealDefinitionIsReady() {
        val lore = LoreExplainResponse(term = "筑基", hanzi = "筑基", definition = "Xây nền tu luyện.")
        assertEquals(LoreOutcome.Ready(lore), LorePolicy.outcome(lore))
    }

    // ── Trắc nghiệm ─────────────────────────────────────────────────────────

    private fun q(id: String, correct: Int = 0, options: List<String> = listOf("a", "b", "c", "d"), text: String = "Câu hỏi?") =
        ComprehensionQuestionItem(id = id, question = text, options = options, correctIndex = correct)

    @Test
    fun quizFallbackSetIsNeverShown() {
        val res = ComprehensionResponse(
            questions = listOf(q("q1"), q("q2"), q("q3")), isFallback = true, error = "AI chưa tạo được câu hỏi cho chương này; đây là bộ câu hỏi mẫu."
        )
        assertEquals(QuizOutcome.Failed(ComprehensionPolicy.MSG_FALLBACK), ComprehensionPolicy.outcome(res))
    }

    @Test
    fun quizNeedsAtLeastTwoValidQuestions() {
        val one = ComprehensionResponse(questions = listOf(q("q1"), q("bad", correct = 9)))
        assertIs<QuizOutcome.Failed>(ComprehensionPolicy.outcome(one))
        assertIs<QuizOutcome.Failed>(ComprehensionPolicy.outcome(ComprehensionResponse()))
    }

    @Test
    fun quizDropsMalformedQuestions() {
        val res = ComprehensionResponse(
            questions = listOf(q("q1"), q("q2", text = " "), q("q3", options = listOf("only")), q("q4", correct = -1), q("q5"))
        )
        val ready = ComprehensionPolicy.outcome(res) as QuizOutcome.Ready
        assertEquals(listOf("q1", "q5"), ready.questions.map { it.id })
    }

    @Test
    fun quizQuestionIdsAreMadeUniqueSoAnswersDoNotCollide() {
        val res = ComprehensionResponse(questions = listOf(q(""), q(""), q("q1"), q("q1"), q("x")))
        val ready = ComprehensionPolicy.outcome(res) as QuizOutcome.Ready
        val ids = ready.questions.map { it.id }
        assertEquals(5, ids.size)
        assertEquals(ids.size, ids.toSet().size, "mã câu hỏi phải duy nhất: $ids")
        assertTrue(ids.none { it.isBlank() })
        assertEquals("x", ids.last(), "mã hợp lệ và duy nhất được giữ nguyên")
    }

    // ── Thông báo lỗi ───────────────────────────────────────────────────────

    @Test
    fun errorMessagesAreFriendly() {
        assertEquals(FriendlyErrors.MSG_SESSION_EXPIRED, AiErrors.message(ApiHttpException(401, "Trò chuyện cùng AI thất bại (mã 401)")))
        assertEquals(FriendlyErrors.MSG_SESSION_EXPIRED, AiErrors.message(IllegalStateException("API error (401): Not authenticated")))
        assertTrue("bận" in AiErrors.message(IllegalStateException("API error (429): rate limited")))
        assertTrue("(mã 503)" in AiErrors.message(ApiHttpException(503, "Tóm tắt thất bại (mã 503)")))
        assertEquals(FriendlyErrors.MSG_UNKNOWN_HOST, AiErrors.message(RuntimeException("Unable to resolve host \"example\"")))
    }
}
