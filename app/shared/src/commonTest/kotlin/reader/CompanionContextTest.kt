package com.truyencloud.shared.reader

import com.truyencloud.shared.api.CompanionChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionContextTest {

    private fun paras(count: Int, length: Int = 300) = List(count) { i -> "P$i " + "x".repeat(length - 3 - i.toString().length) }

    private fun u(text: String) = CompanionChatMessage(CompanionContext.ROLE_USER, text)
    private fun a(text: String) = CompanionChatMessage(CompanionContext.ROLE_ASSISTANT, text)

    // ── Đoạn người đọc đang ở ───────────────────────────────────────────────

    @Test
    fun ttsParagraphWinsWhileListening() {
        assertEquals(7, CompanionContext.centerIndex(20, scrollFraction = 0.1f, ttsActive = true, ttsParagraph = 7))
    }

    @Test
    fun ttsParagraphOutOfRangeFallsBackToScroll() {
        val byScroll = CompanionContext.centerIndex(20, scrollFraction = 0.5f)
        assertEquals(byScroll, CompanionContext.centerIndex(20, scrollFraction = 0.5f, ttsActive = true, ttsParagraph = 99))
    }

    @Test
    fun scrollFractionMapsToTheSameParagraphTheListRestores() {
        // 10 đoạn -> 12 mục (tiêu đề + 10 đoạn + thẻ hết chương); mục 6 là đoạn thứ 5 (đếm từ 0)
        assertEquals(5, CompanionContext.centerIndex(10, scrollFraction = 0.5f))
        assertEquals(0, CompanionContext.centerIndex(10, scrollFraction = 0f))
        assertEquals(9, CompanionContext.centerIndex(10, scrollFraction = 1f))
        // đúng công thức khôi phục: nhảy tới đoạn k rồi đổi ngược ra phải về đúng đoạn k
        for (k in 0 until 10) {
            val position = ReaderScrollRestore.positionForParagraph(k, 10)
            assertEquals(k, CompanionContext.centerIndex(10, scrollFraction = 0f, restoreFraction = position))
        }
    }

    @Test
    fun pendingRestoreIsTheRealPositionNotTheStaleScroll() {
        val restore = ReaderScrollRestore.positionForParagraph(2, 10)
        assertEquals(2, CompanionContext.centerIndex(10, scrollFraction = 0.95f, restoreFraction = restore))
    }

    @Test
    fun noParagraphsMeansIndexZero() {
        assertEquals(0, CompanionContext.centerIndex(0, 0.7f))
    }

    // ── Cửa sổ ngữ cảnh ─────────────────────────────────────────────────────

    @Test
    fun windowIsAroundTheReadingPositionNotTheChapterStart() {
        val p = paras(40)
        val text = CompanionContext.window(p, centerIndex = 25)
        assertTrue("P25 " in text, "đoạn đang đọc phải có mặt")
        assertTrue("P24 " in text && "P20 " in text, "có chữ đã đọc phía trước")
        assertFalse("P0 " in text, "không còn lấy 8 đoạn đầu chương")
        assertTrue(text.length <= CompanionContext.WINDOW_CHARS)
    }

    @Test
    fun windowKeepsParagraphOrderAndWholeParagraphs() {
        val p = paras(30)
        val parts = CompanionContext.window(p, centerIndex = 15).split("\n\n")
        val ids = parts.map { it.substringBefore(' ').removePrefix("P").toInt() }
        assertEquals(ids.sorted(), ids)
        parts.forEach { part -> assertTrue(p.contains(part), "đoạn không bị cắt giữa chừng") }
    }

    @Test
    fun lookAheadIsLimitedToAboutOneScreen() {
        val p = paras(40)
        val text = CompanionContext.window(p, centerIndex = 10)
        val ahead = text.split("\n\n").map { it.substringBefore(' ').removePrefix("P").toInt() }.filter { it > 10 }
        val aheadChars = ahead.sumOf { p[it].length + 2 }
        assertTrue(aheadChars <= CompanionContext.AFTER_CHARS, "không lấn quá một màn hình về phía chưa đọc")
        assertTrue(ahead.isNotEmpty())
    }

    @Test
    fun chapterStartDoesNotHandItsBudgetToTheUnreadSide() {
        // Máy chủ giữ phần CUỐI cửa sổ: nếu ngân sách phía trước bị dồn sang phía sau thì phần giữ lại toàn là chữ chưa đọc
        val p = paras(40)
        val text = CompanionContext.window(p, centerIndex = 0)
        val ids = text.split("\n\n").map { it.substringBefore(' ').removePrefix("P").toInt() }
        assertEquals(0, ids.first())
        assertTrue(text.length < CompanionContext.AFTER_CHARS + 400, "chỉ đoạn đang đọc + một màn hình phía sau")
    }

    @Test
    fun chapterEndGivesTheSpareBudgetToTheReadSide() {
        val p = paras(40)
        val text = CompanionContext.window(p, centerIndex = 39)
        assertTrue("P39 " in text)
        assertTrue(text.length > CompanionContext.WINDOW_CHARS - 400, "cuối chương: dùng gần hết 3000 ký tự cho chữ đã đọc")
        assertTrue(text.length <= CompanionContext.WINDOW_CHARS)
    }

    @Test
    fun hugeCenterParagraphIsCappedButStillPresent() {
        val text = CompanionContext.window(listOf("a", "B".repeat(10_000), "c"), centerIndex = 1)
        assertEquals(CompanionContext.WINDOW_CHARS, text.length)
        assertTrue(text.startsWith("B"))
    }

    @Test
    fun blankParagraphsAreSkippedAndEmptyInputGivesEmpty() {
        assertEquals("", CompanionContext.window(emptyList(), 0))
        assertEquals("one\n\ntwo", CompanionContext.window(listOf("one", "  ", "two"), centerIndex = 2))
        assertEquals("ba", CompanionContext.window(listOf("ba"), centerIndex = 5))
    }

    // ── Lịch sử gửi lên ─────────────────────────────────────────────────────

    @Test
    fun currentQuestionIsNeverInTheRequestHistory() {
        val turn = CompanionContext.prepareTurn(listOf(u("q1"), a("a1")), "q2")
        assertEquals(listOf(u("q1"), a("a1"), u("q2")), turn.shown)
        assertEquals(listOf(u("q1"), a("a1")), turn.request)
        assertEquals(1, turn.shown.count { it.content == "q2" })
        assertEquals(0, turn.request.count { it.content == "q2" })
    }

    @Test
    fun retryDoesNotDuplicateTheFailedQuestion() {
        val failed = listOf(u("q1"), a("a1"), u("q2"))
        val turn = CompanionContext.prepareTurn(failed, "q2", isRetry = true)
        assertEquals(failed, turn.shown)
        assertEquals(listOf(u("q1"), a("a1")), turn.request)
    }

    @Test
    fun anUnansweredEarlierQuestionIsNotSentAsHistory() {
        val turn = CompanionContext.prepareTurn(listOf(u("q1"), a("a1"), u("q2 (lỗi)")), "q3")
        assertEquals(listOf(u("q1"), a("a1")), turn.request)
        assertEquals(4, turn.shown.size)
    }

    @Test
    fun historyIsCappedToTheMostRecentMessages() {
        val long = (1..20).flatMap { listOf(u("q$it"), a("a$it")) }
        val turn = CompanionContext.prepareTurn(long, "q21")
        assertEquals(CompanionContext.MAX_HISTORY, turn.request.size)
        assertEquals("a20", turn.request.last().content)
    }

    // ── Ghi tin trả lời vào lịch sử ─────────────────────────────────────────

    @Test
    fun assistantReplyIsAppendedThenReplacedWhileStreaming() {
        val asked = listOf(u("q1"), a("a1"), u("q2"))
        val first = CompanionContext.withAssistant(asked, index = 3, text = "Xin")
        assertEquals(listOf(u("q1"), a("a1"), u("q2"), a("Xin")), first)
        val longer = CompanionContext.withAssistant(first, index = 3, text = "Xin chào")
        assertEquals(4, longer.size, "mảnh sau thay mảnh trước, không thêm bong bóng mới")
        assertEquals(a("Xin chào"), longer.last())
        assertEquals(asked, listOf(u("q1"), a("a1"), u("q2")), "danh sách gốc không bị sửa")
    }

    @Test
    fun failedTurnDropsTheStreamedApologyButKeepsTheQuestion() {
        val streamed = listOf(u("q1"), a("a1"), u("q2"), a("Xin lỗi, trợ lý AI tạm thời chưa trả lời được."))
        assertEquals(listOf(u("q1"), a("a1"), u("q2")), CompanionContext.withoutAssistant(streamed, index = 3))
        // Chưa kịp có mảnh chữ nào thì không có gì để bỏ
        assertEquals(listOf(u("q1"), a("a1"), u("q2")), CompanionContext.withoutAssistant(listOf(u("q1"), a("a1"), u("q2")), index = 3))
    }

    @Test
    fun theApologyNeverReachesTheNextRequestHistory() {
        val afterFailure = CompanionContext.withoutAssistant(
            CompanionContext.withAssistant(listOf(u("q1")), 1, "Xin lỗi, trợ lý AI tạm thời chưa trả lời được."), 1
        )
        val next = CompanionContext.prepareTurn(afterFailure, "q1", isRetry = true)
        assertTrue(next.request.isEmpty())
        assertTrue(next.shown.none { it.role == CompanionContext.ROLE_ASSISTANT })
    }

    @Test
    fun defaultSuggestionsAreNotEmpty() {
        assertTrue(CompanionContext.DEFAULT_SUGGESTIONS.size >= 3)
    }
}
