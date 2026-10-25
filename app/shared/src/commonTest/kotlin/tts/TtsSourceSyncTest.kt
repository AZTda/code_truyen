package com.truyencloud.shared.tts

import com.truyencloud.shared.text.ParagraphSplitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Đổi bản đọc (Việt <-> Anh) giữa chừng: bộ đọc phải nạp lại đúng đoạn tương ứng thay vì lệch bản đồ về đầu chương. */
class TtsSourceSyncTest {

    private val vi = listOf("Một câu ngắn.", "Hai câu ngắn.", "Ba câu ngắn.", "Bốn câu ngắn.")
    private val en = listOf("First para. Second para.", "Third para. Fourth para.")

    @Test
    fun banAnhVeKhiDangONuaChuongThiTiepTucOGiuaBanAnh() {
        // Lỗi cũ: bộ đọc vẫn ở mảnh Việt #2 nhưng bản đồ đã là bản Anh (2 đoạn) -> đoạn tô sáng ra 0 / lệch
        assertEquals(1, TtsSourceSync.paragraphToResume(old = vi, new = en, speakerSegment = 2))
    }

    @Test
    fun tatCheDoAnhThiQuayLaiBanVietODoanTuongUng() {
        assertEquals(2, TtsSourceSync.paragraphToResume(old = en, new = vi, speakerSegment = 1))
        assertEquals(0, TtsSourceSync.paragraphToResume(old = en, new = vi, speakerSegment = 0))
    }

    @Test
    fun noiDungKhongDoiHoacBanMoiRongThiKhongNapLai() {
        assertNull(TtsSourceSync.paragraphToResume(old = vi, new = vi.toList(), speakerSegment = 1), "Cùng nội dung khác thể hiện: đừng đọc lại")
        assertNull(TtsSourceSync.paragraphToResume(old = vi, new = emptyList(), speakerSegment = 1))
    }

    @Test
    fun hoaTronCungSoDoanThiGiuNguyenViTri() {
        val woven = vi.map { it.replace("câu", "sentence") }
        assertEquals(3, TtsSourceSync.paragraphToResume(old = vi, new = woven, speakerSegment = 3))
    }

    @Test
    fun doanDaiBiCatNhieuManhVanQuyVeDungDoan() {
        val long = "Đây là một câu khá dài để phải cắt nhỏ. ".repeat(12).trim()
        val old = listOf("Mở đầu.", long, "Kết thúc.")
        val segments = ParagraphSplitter.buildTtsSegments(old)
        val inLong = segments.indexOfLast { it.paragraphIndex == 1 }
        assertEquals(true, segments.count { it.paragraphIndex == 1 } > 1, "Đoạn dài phải bị cắt thành nhiều mảnh")
        assertEquals(1, TtsSourceSync.paragraphToResume(old = old, new = old.map { "$it!" }, speakerSegment = inLong))
    }

    @Test
    fun chiSoManhNgoaiKhoangThiVeDauBanMoiKhongLamHong() {
        assertEquals(0, TtsSourceSync.paragraphToResume(old = vi, new = en, speakerSegment = 99))
    }
}
