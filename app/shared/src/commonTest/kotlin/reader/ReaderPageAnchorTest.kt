package com.truyencloud.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderPageAnchorTest {

    /** [paragraphs] đoạn chia đều cho [pages] trang (ít đoạn hơn trang thì một đoạn tràn qua nhiều trang). */
    private fun layout(paragraphs: Int, pages: Int): List<PageSpan> = List(pages) { i ->
        val per = paragraphs.toDouble() / pages
        val first = (i * per).toInt().coerceAtMost(paragraphs - 1)
        PageSpan(first, maxOf(first, ((i + 1) * per - 1e-9).toInt()).coerceAtMost(paragraphs - 1))
    }

    /** Người đọc mở chương ở trang 0 rồi lật tới [page] trong cách chia [spans]. */
    private fun readerAt(spans: List<PageSpan>, page: Int): ReaderPageAnchor {
        val anchor = ReaderPageAnchor()
        assertNull(anchor.observe(spans, 0, canReanchor = false))
        if (page != 0) assertNull(anchor.observe(spans, page, canReanchor = true))
        return anchor
    }

    private fun assertShows(paragraph: Int, spans: List<PageSpan>, page: Int) {
        assertTrue(page in spans.indices, "trang $page nằm ngoài ${spans.size} trang")
        assertTrue(spans[page].first <= paragraph && paragraph <= spans[page].last, "trang $page là ${spans[page]}, không chứa đoạn $paragraph")
    }

    // ── Lỗi gốc: chia trang lại làm người đọc bị kéo lùi ────────────────────

    @Test
    fun enlargingTheFontKeepsTheParagraphBeingRead() {
        val small = layout(paragraphs = 120, pages = 40)   // 3 đoạn / trang
        val large = layout(paragraphs = 120, pages = 60)   // 2 đoạn / trang
        val anchor = readerAt(small, page = 10)             // trang 10 = đoạn 30..32
        assertEquals(30, anchor.paragraph)

        // Pager chưa đo lại với cách chia mới nên currentPage vẫn là 10 — bản cũ coi đó là trang 10 của cách chia mới (đoạn 20)
        val target = anchor.observe(large, currentPage = 10, canReanchor = true)

        assertEquals(15, target, "đoạn 30 nằm ở trang 15 của cách chia 60 trang; ở yên trang 10 là bị kéo lùi về đoạn 20")
        assertShows(30, large, target!!)
        assertEquals(30, anchor.paragraph, "chia lại không được đổi chỗ đang đọc")
    }

    @Test
    fun shrinkingTheFontOrRotatingAlsoLandsOnTheSameParagraph() {
        val portrait = layout(paragraphs = 90, pages = 45)   // 2 đoạn / trang
        val landscape = layout(paragraphs = 90, pages = 90)  // 1 đoạn / trang
        val anchor = readerAt(portrait, page = 20)           // đoạn 40..41
        val target = anchor.observe(landscape, currentPage = 20, canReanchor = true)
        assertEquals(40, target)
        assertShows(40, landscape, target!!)
    }

    @Test
    fun pageCountShrinkingBelowTheCurrentPageStillLandsInRange() {
        val long = layout(paragraphs = 100, pages = 50)      // 2 đoạn / trang
        val short = layout(paragraphs = 100, pages = 20)     // 5 đoạn / trang
        val anchor = readerAt(long, page = 47)               // đoạn 94..95
        val target = anchor.observe(short, currentPage = 47, canReanchor = true)
        assertEquals(18, target)
        assertShows(94, short, target!!)
    }

    @Test
    fun repeatedRepaginationDoesNotDriftTheReadingPlace() {
        val first = layout(paragraphs = 200, pages = 50)
        val anchor = readerAt(first, page = 25)              // đoạn 100..103
        assertEquals(100, anchor.paragraph)
        var page = 25
        // kéo thanh cỡ chữ qua nhiều mức liên tiếp
        for (pages in listOf(70, 40, 90, 33, 120, 61, 50)) {
            val spans = layout(paragraphs = 200, pages = pages)
            val target = anchor.observe(spans, currentPage = page, canReanchor = true)
            val landedOn = target ?: page
            assertShows(100, spans, landedOn)
            if (target != null) anchor.landed(spans, target)
            // lần dựng kế tiếp (pager đã đo lại, đang đứng ở trang vừa cuộn tới) không được ghi đè neo
            assertNull(anchor.observe(spans, currentPage = landedOn, canReanchor = true))
            assertEquals(100, anchor.paragraph, "neo trôi sau khi chia thành $pages trang")
            page = landedOn
        }
    }

    // ── Người đọc tự lật trang thì neo đi theo ──────────────────────────────

    @Test
    fun turningAPageMovesTheAnchor() {
        val spans = layout(paragraphs = 120, pages = 40)
        val anchor = readerAt(spans, page = 10)
        assertNull(anchor.observe(spans, currentPage = 11, canReanchor = true))
        assertEquals(33, anchor.paragraph)
        assertNull(anchor.observe(spans, currentPage = 4, canReanchor = true))
        assertEquals(12, anchor.paragraph)
    }

    @Test
    fun turningAPageAfterALandingIsRecordedButTheLandingItselfIsNot() {
        val small = layout(paragraphs = 120, pages = 40)
        val large = layout(paragraphs = 120, pages = 60)
        val anchor = readerAt(small, page = 10)
        val target = anchor.observe(large, 10, canReanchor = true)!!
        // đang cuộn tới trang mới (pager có thể báo trang tạm thời khác): không phải lựa chọn của người đọc
        assertEquals(target, anchor.observe(large, currentPage = 59, canReanchor = true))
        assertEquals(30, anchor.paragraph)
        anchor.landed(large, target)
        assertNull(anchor.observe(large, currentPage = target, canReanchor = true))
        assertEquals(30, anchor.paragraph, "dừng ở trang neo không được kéo neo về đầu trang")
        assertNull(anchor.observe(large, currentPage = target + 1, canReanchor = true))
        assertEquals(large[target + 1].first, anchor.paragraph)
    }

    @Test
    fun aPageTurnAndARepaginationInTheSamePassAreBothHonoured() {
        val small = layout(paragraphs = 120, pages = 40)
        val large = layout(paragraphs = 120, pages = 60)
        val anchor = readerAt(small, page = 10)
        // người đọc vừa lật sang trang 11 (đoạn 33) cùng lúc cỡ chữ đổi: trang 11 là chỉ số của cách chia CŨ
        val target = anchor.observe(large, currentPage = 11, canReanchor = true)
        assertEquals(33, anchor.paragraph)
        assertEquals(16, target)
        assertShows(33, large, target!!)
    }

    // ── Khi KHÔNG neo lại ───────────────────────────────────────────────────

    @Test
    fun noReanchorWhileTheInitialPositionIsStillBeingRestored() {
        val small = layout(paragraphs = 120, pages = 40)
        val large = layout(paragraphs = 120, pages = 60)
        val anchor = readerAt(small, page = 10)
        assertNull(anchor.observe(large, currentPage = 10, canReanchor = false))
        assertEquals(30, anchor.paragraph)
    }

    @Test
    fun firstPaginationNeverScrollsAndStartsAtTheFirstParagraph() {
        val spans = layout(paragraphs = 30, pages = 10)
        val anchor = ReaderPageAnchor()
        assertNull(anchor.observe(spans, currentPage = 0, canReanchor = true))
        assertEquals(0, anchor.paragraph)
    }

    @Test
    fun readingTheOpeningParagraphsNeedsNoReanchor() {
        val small = layout(paragraphs = 120, pages = 40)
        val large = layout(paragraphs = 120, pages = 60)
        val anchor = readerAt(small, page = 0)
        assertNull(anchor.observe(large, currentPage = 0, canReanchor = true))
    }

    @Test
    fun noScrollWhenTheNewLayoutAlreadyShowsTheParagraphOnTheCurrentPage() {
        val a = layout(paragraphs = 120, pages = 40)
        val b = layout(paragraphs = 120, pages = 41)
        val anchor = readerAt(a, page = 10)                   // đoạn 30
        val target = anchor.observe(b, currentPage = 10, canReanchor = true)
        // trang 10 của cách chia 41 trang chứa đoạn 29..31 -> vẫn là trang này, khỏi cuộn
        assertShows(30, b, 10)
        assertNull(target)
        assertEquals(30, anchor.paragraph)
    }

    // ── Độ bền ──────────────────────────────────────────────────────────────

    @Test
    fun aRelayoutThatChangesNothingIsNotARepagination() {
        // Đoạn 12 dài, mở đầu ở trang 6 và tràn sang trang 7. Người đọc đang ở trang 7 (neo = 12): nếu coi bản chia y hệt là "chia lại"
        // thì neo 12 sẽ kéo họ lùi về trang 6 vô cớ
        val before = listOf(PageSpan(0, 3), PageSpan(4, 6), PageSpan(7, 8), PageSpan(9, 10), PageSpan(11, 11), PageSpan(11, 12), PageSpan(12, 12), PageSpan(12, 13))
        val anchor = readerAt(before, page = 7)
        assertEquals(12, anchor.paragraph)
        val sameContentNewInstance = before.map { it.copy() }
        assertNull(anchor.observe(sameContentNewInstance, currentPage = 7, canReanchor = true))
        assertEquals(12, anchor.paragraph)
    }

    @Test
    fun observeIsRepeatableForTheSamePagination() {
        val small = layout(paragraphs = 120, pages = 40)
        val large = layout(paragraphs = 120, pages = 60)
        val anchor = readerAt(small, page = 10)
        val first = anchor.observe(large, 10, canReanchor = true)
        // lần dựng bị huỷ rồi dựng lại: phải ra đúng kế hoạch đó, không mất neo
        assertEquals(first, anchor.observe(large, 10, canReanchor = true))
        assertEquals(first, anchor.observe(large, 10, canReanchor = true))
        assertEquals(30, anchor.paragraph)
    }

    @Test
    fun aSupersededPlanDoesNotDisturbTheNextOne() {
        val small = layout(paragraphs = 120, pages = 40)
        val mid = layout(paragraphs = 120, pages = 50)
        val large = layout(paragraphs = 120, pages = 60)
        val anchor = readerAt(small, page = 10)
        val midPlan = anchor.observe(mid, 10, canReanchor = true)
        val largePlan = anchor.observe(large, 10, canReanchor = true)   // chia lại tiếp trước khi kịp cuộn
        anchor.landed(mid, midPlan!!)                                           // lần cuộn cũ báo muộn: bị bỏ qua
        assertEquals(largePlan, anchor.observe(large, 10, canReanchor = true))
        assertEquals(30, anchor.paragraph)
    }

    @Test
    fun emptyOrBlankPagesAreHandled() {
        val anchor = ReaderPageAnchor()
        val empty = emptyList<PageSpan>()
        assertNull(anchor.observe(empty, 0, canReanchor = true))
        val blank = listOf(PageSpan(-1, -1))
        assertNull(anchor.observe(blank, 0, canReanchor = true))
        assertEquals(0, anchor.paragraph)
    }

    @Test
    fun aLongParagraphLandsOnThePageWhereItStarts() {
        // đoạn 3 rất dài: bắt đầu cuối trang 2 và tràn qua trang 3, 4
        val before = layout(paragraphs = 6, pages = 6)
        val after = listOf(PageSpan(0, 1), PageSpan(2, 2), PageSpan(2, 3), PageSpan(3, 3), PageSpan(3, 3), PageSpan(4, 5))
        val anchor = readerAt(before, page = 3)               // đoạn 3
        assertEquals(3, anchor.paragraph)
        assertEquals(2, anchor.observe(after, currentPage = 3, canReanchor = true), "trang mở đầu đoạn 3 là trang 2")
    }
}
