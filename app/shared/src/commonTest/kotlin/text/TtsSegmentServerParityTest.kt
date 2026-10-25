package com.truyencloud.shared.text

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * Chữ của từng mảnh TTS mà app gửi lên `/v1/tts/stream` phải TRÙNG từng ký tự với mảnh máy chủ đã dựng sẵn cho cùng chương
 * ([TtsSegmentServerVectors] do bản Python của máy chủ sinh). Lệch một ký tự là trượt tệp đã dựng, phải chờ dựng lại.
 */
class TtsSegmentServerParityTest {

    private fun decode(b64: String): String = Base64.decode(b64).decodeToString()
    private fun expected(b64: String): List<String> = Json.decodeFromString<List<String>>(decode(b64))

    @Test
    fun manhCuaTrinhDocTrungManhMayChuDaDung() {
        for ((name, input, output) in TtsSegmentServerVectors.CASES) {
            val text = decode(input)
            // Đúng đường đi của trình đọc: đoạn theo dòng trống kép -> mảnh TTS
            val got = ParagraphSplitter.ttsTexts(ParagraphSplitter.buildTtsSegments(ParagraphSplitter.splitReaderParagraphs(text)))
            assertEquals(expected(output), got, name)
        }
    }

    @Test
    fun manhTheoDoanHienThiCungTrungMayChu() {
        for ((name, input, output) in TtsSegmentServerVectors.CASES) {
            val text = decode(input)
            val got = ParagraphSplitter.ttsTexts(ParagraphSplitter.buildTtsSegments(ParagraphSplitter.splitDisplayParagraphs(text)))
            assertEquals(expected(output), got, name)
        }
    }

    @Test
    fun xuongDongDonTrongDoanTachManhNhungGiuChiSoDoanHienThi() {
        val paragraphs = ParagraphSplitter.splitReaderParagraphs("Dòng một\nDòng hai\n\nĐoạn ba.")
        assertEquals(2, paragraphs.size)
        val segments = ParagraphSplitter.buildTtsSegments(paragraphs)
        assertEquals(listOf("Dòng một", "Dòng hai", "Đoạn ba."), segments.map { it.text })
        assertEquals(listOf(0, 0, 1), segments.map { it.paragraphIndex })
        assertEquals(2, ParagraphSplitter.firstSegmentOfParagraph(segments, 1))
    }

    @Test
    fun doanChiCoTheHtmlThiDocTuDoanCoChuKeTiep() {
        val segments = ParagraphSplitter.buildTtsSegments(listOf("Một.", "<p></p>", "Ba."))
        assertEquals(listOf("Một.", "Ba."), segments.map { it.text })
        assertEquals(1, ParagraphSplitter.firstSegmentOfParagraph(segments, 1), "Không quay về đầu chương")
    }
}
