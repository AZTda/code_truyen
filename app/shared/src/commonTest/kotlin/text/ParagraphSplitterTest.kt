package com.truyencloud.shared.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParagraphSplitterTest {

    private val shortPara = "Cô khẽ mỉm cười rồi quay đi."
    private val longPara = buildString {
        repeat(12) { append("Đây là câu thứ $it trong một đoạn văn rất dài. ") }
    }.trim()

    @Test
    fun doanHienThiGiuNguyenRanhGioiCuaTacGia() {
        val text = "$shortPara\n$longPara\n$shortPara"
        val display = ParagraphSplitter.splitDisplayParagraphs(text)

        assertEquals(3, display.size, "Phải giữ đúng 3 đoạn như văn bản gốc")
        assertEquals(longPara, display[1], "Đoạn dài KHÔNG được cắt nhỏ khi hiển thị")
        assertTrue(display[1].length > ParagraphSplitter.TTS_SPLIT_THRESHOLD)
    }

    @Test
    fun manhTtsDuocCatNhoNhungVanTroVeDungDoanCha() {
        val display = ParagraphSplitter.splitDisplayParagraphs("$shortPara\n$longPara")
        val segments = ParagraphSplitter.buildTtsSegments(display)

        assertTrue(segments.size > display.size, "Đoạn dài phải được cắt thành nhiều mảnh")
        assertEquals(0, segments.first().paragraphIndex)
        assertTrue(
            segments.all { it.paragraphIndex in display.indices },
            "Mọi mảnh phải trỏ về một đoạn hợp lệ"
        )
        // Mảnh cuối cùng thuộc đoạn dài (đoạn số 1)
        assertEquals(1, segments.last().paragraphIndex)
    }

    @Test
    fun doanNganGiuNguyenMotManh() {
        val display = listOf(shortPara, shortPara)
        val segments = ParagraphSplitter.buildTtsSegments(display)
        assertEquals(2, segments.size)
        assertEquals(shortPara, segments[0].text)
    }

    @Test
    fun doiChiSoManhSangChiSoDoan() {
        val display = ParagraphSplitter.splitDisplayParagraphs("$shortPara\n$longPara")
        val segments = ParagraphSplitter.buildTtsSegments(display)

        assertEquals(0, ParagraphSplitter.paragraphIndexForSegment(segments, 0))
        assertEquals(1, ParagraphSplitter.paragraphIndexForSegment(segments, segments.size - 1))
        // Chỉ số ngoài phạm vi không được làm sập ứng dụng
        assertEquals(0, ParagraphSplitter.paragraphIndexForSegment(segments, 9999))
    }

    @Test
    fun timManhDauTienCuaMotDoan() {
        val display = ParagraphSplitter.splitDisplayParagraphs("$shortPara\n$longPara\n$shortPara")
        val segments = ParagraphSplitter.buildTtsSegments(display)

        assertEquals(0, ParagraphSplitter.firstSegmentOfParagraph(segments, 0))
        val firstOfSecond = ParagraphSplitter.firstSegmentOfParagraph(segments, 1)
        assertEquals(1, segments[firstOfSecond].paragraphIndex)
    }

    @Test
    fun ghepSongNguKhiSoDoanBangNhau() {
        val vi = listOf("Việt 1", "Việt 2", "Việt 3")
        assertEquals("Việt 2", ParagraphSplitter.pairBilingual(1, 3, vi))
    }

    @Test
    fun ghepSongNguKhiSoDoanLechNhau() {
        // Ghép đoạn 1-1: đoạn n tiếng Anh tương ứng với đoạn n tiếng Việt
        val vi = listOf("Việt 1", "Việt 2", "Việt 3")
        assertEquals("Việt 1", ParagraphSplitter.pairBilingual(0, 6, vi))
        assertEquals("Việt 2", ParagraphSplitter.pairBilingual(1, 6, vi))
        assertEquals("Việt 3", ParagraphSplitter.pairBilingual(2, 6, vi))
        assertNull(ParagraphSplitter.pairBilingual(3, 6, vi))
        assertNull(ParagraphSplitter.pairBilingual(5, 6, vi))
    }

    @Test
    fun tachDoanSongNguDinhDangTag() {
        val raw = """
            [EN] This is sentence one.
            [VI] Đây là câu một.
            
            [EN] This is sentence two.
            [VI] Đây là câu hai.
        """.trimIndent()
        val parsed = ParagraphSplitter.splitBilingualPairs(raw)
        assertNotNull(parsed)
        assertEquals(2, parsed.englishParagraphs.size)
        assertEquals(2, parsed.vietnameseParagraphs.size)
        assertEquals("This is sentence one.", parsed.englishParagraphs[0])
        assertEquals("Đây là câu một.", parsed.vietnameseParagraphs[0])
    }

    @Test
    fun ghepSongNguVoiDanhSachRong() {
        assertNull(ParagraphSplitter.pairBilingual(0, 3, emptyList()))
        assertNull(ParagraphSplitter.pairBilingual(0, 0, listOf("x")))
    }

    @Test
    fun locBoTheHtmlVaDongTrong() {
        val raw = "<p>Đoạn một</p><br/>\n\n   \n<p>Đoạn hai</p>"
        val display = ParagraphSplitter.splitDisplayParagraphs(raw)
        assertEquals(listOf("Đoạn một", "Đoạn hai"), display)
    }

    @Test
    fun vanBanRongTraVeDanhSachRong() {
        assertTrue(ParagraphSplitter.splitDisplayParagraphs("").isEmpty())
        assertTrue(ParagraphSplitter.splitDisplayParagraphs("   \n  ").isEmpty())
    }

    @Test
    fun khongManhNaoVuotQuaTranDoDai() {
        val display = ParagraphSplitter.splitDisplayParagraphs(longPara)
        val segments = ParagraphSplitter.buildTtsSegments(display)
        // Cho phép vượt nhẹ vì không cắt giữa câu, nhưng không được gấp đôi
        assertTrue(
            segments.all { it.text.length <= ParagraphSplitter.TTS_SEGMENT_MAX * 2 },
            "Mảnh dài nhất: ${segments.maxOf { it.text.length }}"
        )
    }
}
