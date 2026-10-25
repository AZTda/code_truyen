package com.truyencloud.shared.api

import com.truyencloud.shared.reader.CompanionPolicy
import com.truyencloud.shared.reader.CompanionResult
import com.truyencloud.shared.reader.ComprehensionPolicy
import com.truyencloud.shared.reader.LoreOutcome
import com.truyencloud.shared.reader.LorePolicy
import com.truyencloud.shared.reader.QuizOutcome
import com.truyencloud.shared.reader.RecapOutcome
import com.truyencloud.shared.reader.RecapPolicy
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Các phản hồi dưới đây được BẮT NGUYÊN VĂN từ máy chủ cuối cùng (TestClient, AI hỏng / không có Antigravity CLI) rồi giải mã
 * bằng đúng cấu hình Json của ứng dụng: bảo đảm tên trường `is_fallback` / `error` / `chapters_covered` / `terms` khớp DTO và
 * mỗi kết quả dự phòng đi đúng nhánh "lỗi + thử lại" thay vì hiện như nội dung thật.
 */
class AiServerPayloadTest {
    // Cùng cấu hình với TruyenCloudApi.httpClient
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val recapChapterOne = """{"novel_id": "nr", "chapter_number": 1, "novel_title": "Truyen R", "chapter_title": "Chương 1",
        "bullets": ["Khởi đầu hành trình trong tác phẩm Truyen R.", "Mở ra bối cảnh mở màn tại Chương 1."], "characters": [],
        "context": "Chương mở đầu của câu chuyện Truyen R.", "chapters_covered": [], "is_fallback": true, "error": null}"""

    private val recapAiFailed = """{"novel_id": "nr", "chapter_number": 3, "novel_title": "Truyen R", "chapter_title": "Chương 3",
        "bullets": [], "characters": [], "context": "", "chapters_covered": [], "is_fallback": true,
        "error": "AI chưa tóm tắt được các chương trước. Vui lòng thử lại sau."}"""

    private val recapReal = """{"novel_id": "nr", "chapter_number": 10, "novel_title": "T", "chapter_title": "Chương 10",
        "bullets": ["Sự kiện A8", "Sự kiện B9"], "characters": ["Lâm Động (chính)"], "context": "Cuối chương 9",
        "chapters_covered": [8, 9], "is_fallback": false, "error": null}"""

    private val loreFallback = """{"term": "筑基", "hanzi": "", "pinyin": "", "sino_vietnamese": "", "definition": "", "lore_context": "",
        "is_fallback": true, "error": "Antigravity CLI chưa sẵn sàng trên máy chủ."}"""

    private val loreReal = """{"term": "筑基", "hanzi": "筑基", "pinyin": "zhùjī", "sino_vietnamese": "Trúc Cơ",
        "definition": "Xây nền tu luyện.", "lore_context": "", "is_fallback": false, "error": null}"""

    private val quizFallback = """{"chapter_title": "Ch 1", "questions": [
        {"id": "q1", "question": "What is the central focus?", "options": ["a", "b", "c", "d"], "correct_index": 1, "explanation": "x", "focus_type": "plot"},
        {"id": "q2", "question": "Which theme?", "options": ["a", "b", "c", "d"], "correct_index": 2, "explanation": "y", "focus_type": "vocabulary"},
        {"id": "q3", "question": "How does the protagonist?", "options": ["a", "b", "c", "d"], "correct_index": 0, "explanation": "z", "focus_type": "character"}],
        "is_fallback": true, "error": "AI chưa tạo được câu hỏi cho chương này; đây là bộ câu hỏi mẫu."}"""

    private val quizReal = """{"chapter_title": "Ch 2", "questions": [
        {"question": "Q1?", "options": ["right", "w1", "w2", "w3"], "correct_index": 0, "explanation": "x"},
        {"question": "Q2?", "options": ["right", "w1", "w2", "w3"], "correct_index": 2, "explanation": "x"}],
        "is_fallback": false, "error": null}"""

    private val companionJsonFallback = """{"reply": "Xin lỗi, trợ lý AI tạm thời chưa trả lời được câu này. Bạn thử hỏi lại sau ít phút nhé.",
        "character_name": "Trợ Lý Bách Khoa", "suggested_questions": [], "is_fallback": true, "error": "Antigravity CLI chưa sẵn sàng trên máy chủ."}"""

    private val companionJsonReal = """{"reply": "Ta chính là Lý Thiên Mệnh!", "character_name": "Lý Thiên Mệnh",
        "suggested_questions": ["Công pháp của ngươi là gì?"], "is_fallback": false, "error": null}"""

    // ── Tóm tắt chương ──────────────────────────────────────────────────────

    @Test
    fun staticChapterOneRecapIsHiddenNotShownAsASummary() {
        val recap = json.decodeFromString<ChapterRecapResponse>(recapChapterOne)
        assertTrue(recap.is_fallback)
        assertEquals(null, recap.error)
        assertEquals(RecapOutcome.NothingToRecap, RecapPolicy.outcome(1, recap))
        // Kể cả khi một chương khác nhận phải nội dung tĩnh này (không có lỗi kèm theo) cũng không được hiện
        assertEquals(RecapOutcome.NothingToRecap, RecapPolicy.outcome(2, recap))
    }

    @Test
    fun failedRecapShowsTheServersReasonWithRetry() {
        val recap = json.decodeFromString<ChapterRecapResponse>(recapAiFailed)
        assertEquals(RecapOutcome.Failed("AI chưa tóm tắt được các chương trước. Vui lòng thử lại sau."), RecapPolicy.outcome(3, recap))
    }

    @Test
    fun realRecapIsShown() {
        val recap = json.decodeFromString<ChapterRecapResponse>(recapReal)
        assertEquals(listOf(8, 9), recap.chapters_covered)
        assertEquals(RecapOutcome.Ready(recap), RecapPolicy.outcome(10, recap))
    }

    // ── Tra cứu ─────────────────────────────────────────────────────────────

    @Test
    fun fallbackLoreIsAnErrorAndNeverADefinition() {
        val lore = json.decodeFromString<LoreExplainResponse>(loreFallback)
        assertTrue(lore.is_fallback)
        assertEquals(LoreOutcome.Failed("Antigravity CLI chưa sẵn sàng trên máy chủ."), LorePolicy.outcome(lore))
    }

    @Test
    fun realLoreIsReady() {
        val lore = json.decodeFromString<LoreExplainResponse>(loreReal)
        assertFalse(lore.is_fallback)
        assertEquals(LoreOutcome.Ready(lore), LorePolicy.outcome(lore))
    }

    // ── Trắc nghiệm ─────────────────────────────────────────────────────────

    @Test
    fun fallbackQuizIsABannerNotAQuiz() {
        val quiz = json.decodeFromString<ComprehensionResponse>(quizFallback)
        assertTrue(quiz.isFallback)
        assertEquals(3, quiz.questions.size, "máy chủ vẫn gửi kèm câu hỏi mẫu — ứng dụng không được hiện chúng")
        assertEquals(QuizOutcome.Failed(ComprehensionPolicy.MSG_FALLBACK), ComprehensionPolicy.outcome(quiz))
    }

    @Test
    fun realQuizGetsUniqueIdsEvenWhenTheServerOmitsThem() {
        val quiz = json.decodeFromString<ComprehensionResponse>(quizReal)
        assertFalse(quiz.isFallback)
        val ready = assertIs<QuizOutcome.Ready>(ComprehensionPolicy.outcome(quiz))
        assertEquals(listOf("q1", "q2"), ready.questions.map { it.id })
    }

    // ── AI Companion (đường JSON) ───────────────────────────────────────────

    @Test
    fun companionJsonFallbackIsAFailureBubble() {
        val res = json.decodeFromString<CompanionChatResponse>(companionJsonFallback)
        assertTrue(res.is_fallback)
        assertEquals("Antigravity CLI chưa sẵn sàng trên máy chủ.", res.error)
        val out = assertIs<CompanionResult.Failed>(CompanionPolicy.classify(res))
        assertTrue(out.message.startsWith("Xin lỗi"))
    }

    @Test
    fun companionJsonAnswerKeepsTheServersSuggestions() {
        val res = json.decodeFromString<CompanionChatResponse>(companionJsonReal)
        val out = assertIs<CompanionResult.Answer>(CompanionPolicy.classify(res))
        assertEquals(listOf("Công pháp của ngươi là gì?"), out.suggestions)
    }

    @Test
    fun companionRequestCarriesTheChapterNumberAndOmitsItWhenUnknown() {
        val known = json.encodeToString(CompanionChatRequest.serializer(), CompanionChatRequest(novel_title = "T", chapter_number = 1, user_message = "q"))
        assertTrue("\"chapter_number\":1" in known, "chương 1 vẫn phải được gửi (bản cũ mặc định 1 nên không phân biệt được)")
        val unknown = json.encodeToString(CompanionChatRequest.serializer(), CompanionChatRequest(novel_title = "T", user_message = "q"))
        assertFalse("chapter_number" in unknown)
    }

    // ── Bảng thuật ngữ máy chủ ──────────────────────────────────────────────

    @Test
    fun serverGlossaryDecodesTheAutoFlag() {
        val res = json.decodeFromString<GlossaryServerResponse>(
            """{"terms":[{"source":"岑星","target":"Sầm Tinh","auto":true},{"source":"天玄宗","target":"Thiên Huyền Tông","auto":false},{"source":"林动","target":"Lâm Động"}]}"""
        )
        assertEquals(listOf(true, false, false), res.terms.map { it.auto })
        assertEquals(0, json.decodeFromString<GlossaryServerResponse>("""{"terms": []}""").terms.size)
    }
}
