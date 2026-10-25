package com.truyencloud.shared.reader

import com.truyencloud.shared.data.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderTocTest {
    private fun chapter(n: Int, vi: String = "", raw: String = "第${n}章") =
        Chapter(id = "b_$n", novelId = "b", chapterNumber = n, title = raw, titleVi = vi)

    private val toc = ReaderToc.index(
        listOf(
            chapter(1, "Khởi đầu"),
            chapter(2, "Kiếm Lai"),
            chapter(3, "Phong vân tái khởi"),
            chapter(12, "Thiên kiếm xuất thế"),
            chapter(120, "Hồi kết")
        )
    )

    @Test
    fun blankQueryKeepsEverythingInOrder() {
        assertEquals(listOf(1, 2, 3, 12, 120), ReaderToc.filter(toc, "  ", descending = false).map { it.chapterNumber })
    }

    @Test
    fun descendingPutsNewestFirst() {
        assertEquals(listOf(120, 12, 3, 2, 1), ReaderToc.filter(toc, "", descending = true).map { it.chapterNumber })
    }

    @Test
    fun titleSearchIgnoresCaseAndDiacritics() {
        assertEquals(listOf(2, 12), ReaderToc.filter(toc, "kiem", descending = false).map { it.chapterNumber })
        assertEquals(listOf(2, 12), ReaderToc.filter(toc, "KIẾM", descending = false).map { it.chapterNumber })
        assertEquals(listOf(3), ReaderToc.filter(toc, "phong van", descending = false).map { it.chapterNumber })
    }

    @Test
    fun numericQueryMatchesChapterNumberPrefix() {
        assertEquals(listOf(12, 120), ReaderToc.filter(toc, "12", descending = false).map { it.chapterNumber })
        assertEquals(listOf(120, 12), ReaderToc.filter(toc, "12", descending = true).map { it.chapterNumber })
    }

    @Test
    fun noMatchGivesEmptyList() {
        assertTrue(ReaderToc.filter(toc, "khong co", descending = false).isEmpty())
    }

    @Test
    fun jumpParsing() {
        assertEquals(TocJump.Go(57), ReaderToc.parseJump(" 57 ", lastChapter = 2000))
        assertEquals(TocJump.Go(2000), ReaderToc.parseJump("2000", lastChapter = 2000))
        assertEquals(TocJump.OutOfRange(2000), ReaderToc.parseJump("2001", lastChapter = 2000))
        assertEquals(TocJump.Go(9999), ReaderToc.parseJump("9999", lastChapter = 0))
        assertEquals(TocJump.Invalid, ReaderToc.parseJump("", lastChapter = 10))
        assertEquals(TocJump.Invalid, ReaderToc.parseJump("abc", lastChapter = 10))
        assertEquals(TocJump.Invalid, ReaderToc.parseJump("0", lastChapter = 10))
        assertEquals(TocJump.Invalid, ReaderToc.parseJump("-4", lastChapter = 10))
    }

    @Test
    fun duplicateChapterNumbersCollapseToOneRow() {
        val entries = ReaderToc.index(listOf(chapter(1, "A"), chapter(2, "B"), chapter(2, "B lặp"), chapter(3, "C")))
        assertEquals(listOf(1, 2, 3), ReaderToc.filter(entries, "", descending = false).map { it.chapterNumber })
    }

    @Test
    fun positionLookup() {
        val list = ReaderToc.filter(toc, "", descending = true)
        assertEquals(4, ReaderToc.positionOf(list, 1))
        assertEquals(-1, ReaderToc.positionOf(list, 99))
    }

    @Test
    fun indexingFiveThousandChaptersIsCheap() {
        val big = (1..5000).map { chapter(it, "Chương số $it: Kiếm khách") }
        val entries = ReaderToc.index(big)
        assertEquals(5000, ReaderToc.filter(entries, "kiem", descending = true).size)
        assertEquals(1, ReaderToc.filter(entries, "4999", descending = false).size)
    }
}
