package com.truyencloud.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionSseTest {

    private fun feed(acc: CompanionStreamAccumulator, vararg lines: String): List<String> =
        lines.mapNotNull { acc.accept(CompanionSse.parseLine(it))?.content }

    @Test
    fun parsesTheThreeEventTypes() {
        val start = CompanionSse.parseLine("""data: {"type": "start", "character_name": "Lý Thiên Mệnh"}""")
        assertEquals(CompanionSseEvent.Start("Lý Thiên Mệnh"), start)
        val chunk = CompanionSse.parseLine("""data: {"type": "chunk", "content": "Xin chào"}""")
        assertEquals(CompanionSseEvent.Chunk("Xin chào"), chunk)
        val done = CompanionSse.parseLine(
            """data: {"type": "done", "character_name": "X", "suggested_questions": ["a", " ", "b"], "is_fallback": false, "error": null}"""
        )
        assertEquals(CompanionSseEvent.Done("X", listOf("a", "b"), isFallback = false, error = null), done)
    }

    @Test
    fun doneCarriesTheFallbackFlagAndReason() {
        val done = CompanionSse.parseLine(
            """data: {"type": "done", "character_name": "Trợ Lý", "suggested_questions": [], "is_fallback": true, "error": "AI không trả lời hợp lệ."}"""
        ) as CompanionSseEvent.Done
        assertTrue(done.isFallback)
        assertEquals("AI không trả lời hợp lệ.", done.error)
        assertTrue(done.suggestedQuestions.isEmpty())
    }

    @Test
    fun ignoresNoiseAndBrokenJson() {
        assertNull(CompanionSse.parseLine(""))
        assertNull(CompanionSse.parseLine(": keep-alive"))
        assertNull(CompanionSse.parseLine("data: "))
        assertNull(CompanionSse.parseLine("data: {not json"))
        assertNull(CompanionSse.parseLine("""data: {"type": "unknown"}"""))
        assertNull(CompanionSse.parseLine("""data: ["array"]"""))
    }

    @Test
    fun normalStreamBecomesAnAnswer() {
        val acc = CompanionStreamAccumulator(requestedCharacter = null)
        val shown = feed(
            acc,
            """data: {"type": "start", "character_name": "Trợ Lý Đọc Sách Thông Thái"}""",
            """data: {"type": "chunk", "content": "Nhân vật "}""",
            """data: {"type": "chunk", "content": "chính đang tính kế."}""",
            """data: {"type": "done", "character_name": "Trợ Lý Đọc Sách Thông Thái", "suggested_questions": [], "is_fallback": false, "error": null}"""
        )
        assertEquals(listOf("Nhân vật ", "chính đang tính kế."), shown)
        val res = acc.toResponse()!!
        assertEquals("Nhân vật chính đang tính kế.", res.reply)
        assertFalse(res.is_fallback)
        assertNull(res.error)
        assertTrue(res.suggested_questions.isEmpty(), "luồng SSE không có câu hỏi gợi ý")
    }

    @Test
    fun cannedApologyStreamedAsChunkIsFlaggedByDone() {
        val acc = CompanionStreamAccumulator("Nhân vật chính")
        feed(
            acc,
            """data: {"type": "start", "character_name": "Nhân vật chính"}""",
            """data: {"type": "chunk", "content": "Xin lỗi, trợ lý AI tạm thời chưa trả lời được câu này."}""",
            """data: {"type": "done", "character_name": "Nhân vật chính", "suggested_questions": [], "is_fallback": true, "error": "AI không trả lời hợp lệ."}"""
        )
        val res = acc.toResponse()!!
        assertTrue(res.is_fallback, "câu xin lỗi dự phòng không được coi là câu trả lời thật")
        assertEquals("AI không trả lời hợp lệ.", res.error)
        assertTrue(res.reply.startsWith("Xin lỗi"))
    }

    /** Nguyên văn luồng thật do máy chủ cuối cùng trả về (bắt từ TestClient khi AI hỏng), gồm cả dòng trống giữa các sự kiện. */
    private val serverFallbackTranscript =
        "data: {\"type\": \"start\", \"character_name\": \"Ly Thien Menh\"}\n\n" +
            "data: {\"type\": \"chunk\", \"content\": \"Xin lỗi, trợ lý AI tạm thời chưa trả lời được câu này. Bạn thử hỏi lại sau ít phút nhé.\"}\n\n" +
            "data: {\"type\": \"done\", \"character_name\": \"Ly Thien Menh\", \"suggested_questions\": [], \"is_fallback\": true, " +
            "\"error\": \"Antigravity CLI chưa sẵn sàng trên máy chủ.\"}\n\n"

    private val serverOkTranscript =
        "data: {\"type\": \"start\", \"character_name\": \"Trợ Lý Đọc Sách Thông Thái\"}\n\n" +
            "data: {\"type\": \"chunk\", \"content\": \"Xin \"}\n\ndata: {\"type\": \"chunk\", \"content\": \"chao\"}\n\n" +
            "data: {\"type\": \"done\", \"character_name\": \"Trợ Lý Đọc Sách Thông Thái\", \"suggested_questions\": [], " +
            "\"is_fallback\": false, \"error\": null}\n\n"

    @Test
    fun realServerFallbackTranscriptEndsAsAFlaggedFailure() {
        val acc = CompanionStreamAccumulator("Ly Thien Menh")
        val shown = feed(acc, *serverFallbackTranscript.split("\n").toTypedArray())
        assertEquals(1, shown.size, "câu xin lỗi tới như một mảnh chữ bình thường")
        val res = acc.toResponse()!!
        assertTrue(res.is_fallback)
        assertEquals("Antigravity CLI chưa sẵn sàng trên máy chủ.", res.error)
        assertEquals("Ly Thien Menh", res.character_name)
    }

    @Test
    fun realServerSuccessTranscriptEndsAsAnAnswer() {
        val acc = CompanionStreamAccumulator(null)
        val shown = feed(acc, *serverOkTranscript.split("\n").toTypedArray())
        assertEquals(listOf("Xin ", "chao"), shown)
        val res = acc.toResponse()!!
        assertFalse(res.is_fallback)
        assertNull(res.error)
        assertEquals("Xin chao", res.reply)
        assertEquals("Trợ Lý Đọc Sách Thông Thái", res.character_name)
    }

    @Test
    fun emptyStreamHasNoResponseSoCallerCanTryJson() {
        val acc = CompanionStreamAccumulator(null)
        assertNull(acc.toResponse())
        feed(acc, ": comment", "")
        assertNull(acc.toResponse())
        assertFalse(acc.hasContent)
    }

    @Test
    fun streamCutAfterSomeTextKeepsThePartialAnswer() {
        val acc = CompanionStreamAccumulator("Sư phụ")
        feed(acc, """data: {"type": "chunk", "content": "Con hãy kiên nhẫn"}""")
        val res = assertNotNull(acc.toResponse())
        assertEquals("Con hãy kiên nhẫn", res.reply)
        assertFalse(res.is_fallback)
        assertEquals("Sư phụ", res.character_name)
    }

    @Test
    fun characterNamePrecedenceIsDoneThenStartThenRequestedThenDefault() {
        val onlyText = """data: {"type": "chunk", "content": "x"}"""
        assertEquals(DEFAULT_COMPANION_NAME, CompanionStreamAccumulator(null).also { feed(it, onlyText) }.toResponse()!!.character_name)
        assertEquals("Req", CompanionStreamAccumulator("Req").also { feed(it, onlyText) }.toResponse()!!.character_name)
        val withStart = CompanionStreamAccumulator("Req").also {
            feed(it, """data: {"type": "start", "character_name": "FromStart"}""", onlyText)
        }
        assertEquals("FromStart", withStart.toResponse()!!.character_name)
        val withDone = CompanionStreamAccumulator("Req").also {
            feed(it, """data: {"type": "start", "character_name": "FromStart"}""", onlyText, """data: {"type": "done", "character_name": "FromDone"}""")
        }
        assertEquals("FromDone", withDone.toResponse()!!.character_name)
    }

    // ── CompanionStreamRun: luồng không có sự kiện `done` là câu trả lời CHƯA trọn vẹn ──────────────────────

    private fun run(vararg lines: String, requested: String? = null): Pair<CompanionStreamRun, List<String>> {
        val stream = CompanionStreamRun(requested)
        return stream to lines.mapNotNull { stream.accept(it) }
    }

    @Test
    fun aStreamThatEndsWithDoneIsComplete() {
        val (stream, shown) = run(
            """data: {"type": "chunk", "content": "Xin "}""",
            """data: {"type": "chunk", "content": "chao"}""",
            """data: {"type": "done", "character_name": "Trợ Lý", "suggested_questions": [], "is_fallback": false, "error": null}"""
        )
        assertEquals(listOf("Xin ", "chao"), shown)
        val res = stream.toResponse()!!
        assertFalse(res.is_incomplete)
        assertEquals("Xin chao", res.reply)
    }

    @Test
    fun aStreamCutAfterSomeTextIsMarkedIncompleteSoItIsNeverShownAsAFullAnswer() {
        // Lỗi gốc: mạng rớt giữa chừng / máy chủ đóng sớm -> nửa câu được lưu như câu trả lời thật, không có nút thử lại
        val (stream, _) = run(
            """data: {"type": "start", "character_name": "Sư phụ"}""",
            """data: {"type": "chunk", "content": "Nửa câu trả lời bị cắt"}"""
        )
        val res = stream.toResponse()!!
        assertTrue(res.is_incomplete)
        assertEquals("Nửa câu trả lời bị cắt", res.reply)
        assertFalse(res.is_fallback)
        assertNull(res.error)
        assertEquals("Sư phụ", res.character_name)
    }

    @Test
    fun doneWithoutAnyTextIsStillComplete() {
        val (stream, _) = run(
            """data: {"type": "done", "character_name": "Trợ Lý", "suggested_questions": [], "is_fallback": true, "error": "AI hỏng"}"""
        )
        val res = stream.toResponse()!!
        assertFalse(res.is_incomplete, "đã có done = máy chủ chủ động kết thúc (kể cả báo dự phòng)")
        assertTrue(res.is_fallback)
    }

    @Test
    fun anEmptyStreamHasNoResponseSoTheCallerTriesJson() {
        val (stream, _) = run(": keep-alive", "")
        assertNull(stream.toResponse())
        assertFalse(stream.hasContent)
    }

    @Test
    fun textThenDoneThenNoiseStaysComplete() {
        val (stream, _) = run(
            """data: {"type": "chunk", "content": "A"}""",
            """data: {"type": "done", "character_name": "X", "suggested_questions": ["q"], "is_fallback": false, "error": null}""",
            ": trailing comment"
        )
        val res = stream.toResponse()!!
        assertFalse(res.is_incomplete)
        assertEquals(listOf("q"), res.suggested_questions)
    }
}
