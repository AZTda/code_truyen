package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtsProgressMathTest {

    @Test
    fun luuRoiKhoiPhucGiuDungDoan() {
        for (count in listOf(1, 2, 7, 40, 311)) {
            for (i in 0 until count) {
                val scroll = TtsProgressMath.scrollForParagraph(i, count)
                assertTrue(scroll in 0f..1f, "scroll=$scroll ngoài [0,1]")
                assertEquals(i, TtsProgressMath.paragraphForScroll(scroll, count), "i=$i count=$count")
            }
        }
    }

    @Test
    fun viTriCuonLuonDuong() {
        // restoreScrollPosition > 0 mới được khôi phục: đoạn đầu không được ra 0
        assertTrue(TtsProgressMath.scrollForParagraph(0, 50) > 0f)
    }

    @Test
    fun giaTriBienKhongLamHong() {
        assertEquals(0f, TtsProgressMath.scrollForParagraph(3, 0))
        assertEquals(0, TtsProgressMath.paragraphForScroll(0.5f, 0))
        assertEquals(0, TtsProgressMath.paragraphForScroll(0f, 10))
        assertEquals(0, TtsProgressMath.paragraphForScroll(-1f, 10))
        assertEquals(9, TtsProgressMath.paragraphForScroll(5f, 10))
        assertEquals(
            TtsProgressMath.scrollForParagraph(9, 10),
            TtsProgressMath.scrollForParagraph(99, 10),
            "Chỉ số vượt quá thì kẹp về đoạn cuối"
        )
    }

    @Test
    fun khopCongThucLuuViTriCuaTrinhDoc() {
        // Trình đọc lưu: mục đầu tiên nhìn thấy / (tổng mục - 1), tổng mục = N + 2 (tiêu đề + N đoạn + cuối chương)
        val n = 40
        val firstVisibleItem = 12 // = đoạn số 11 (mục 0 là tiêu đề)
        val savedByReader = firstVisibleItem.toFloat() / (n + 1).toFloat()
        assertEquals(11, TtsProgressMath.paragraphForScroll(savedByReader, n))
        assertEquals(savedByReader, TtsProgressMath.scrollForParagraph(11, n))
    }

    @Test
    fun nhanDoanTheoDoanHienThiKhopThanhDieuKhien() {
        // 5 mảnh TTS thuộc 3 đoạn hiển thị: thanh điều khiển ghi "đoạn/3", thông báo cũng phải vậy
        val map = listOf(0, 0, 1, 2, 2)
        assertEquals("Đoạn 1/3", TtsProgressMath.paragraphLabel(1, map, 3, 5))
        assertEquals("Đoạn 2/3", TtsProgressMath.paragraphLabel(2, map, 3, 5))
        assertEquals("Đoạn 3/3", TtsProgressMath.paragraphLabel(4, map, 3, 5))
    }

    @Test
    fun khongCoBanDoThiDungSoManh() {
        assertEquals("Đoạn 3/9", TtsProgressMath.paragraphLabel(2, emptyList(), 0, 9))
        assertEquals("", TtsProgressMath.paragraphLabel(0, emptyList(), 0, 0))
    }

    @Test
    fun doanTuongUngKhiDoiBanGiuDungViTriTheoTiLe() {
        // Bản Việt 4 đoạn -> bản Anh gộp 2 đoạn: đoạn 2 (giữa chương) phải sang đoạn 1, không về 0
        assertEquals(1, TtsProgressMath.equivalentParagraph(index = 2, oldCount = 4, newCount = 2))
        assertEquals(0, TtsProgressMath.equivalentParagraph(index = 1, oldCount = 4, newCount = 2))
        assertEquals(1, TtsProgressMath.equivalentParagraph(index = 3, oldCount = 4, newCount = 2))
        // ngược lại: 2 đoạn -> 4 đoạn
        assertEquals(2, TtsProgressMath.equivalentParagraph(index = 1, oldCount = 2, newCount = 4))
    }

    @Test
    fun haiBanCungSoDoanThiGiuNguyenChiSo() {
        for (i in 0 until 7) assertEquals(i, TtsProgressMath.equivalentParagraph(i, 7, 7))
    }

    @Test
    fun doanTuongUngLuonNamTrongBanMoi() {
        assertEquals(0, TtsProgressMath.equivalentParagraph(0, 10, 3))
        assertEquals(2, TtsProgressMath.equivalentParagraph(9, 10, 3))
        assertEquals(2, TtsProgressMath.equivalentParagraph(99, 10, 3), "Chỉ số vượt quá thì kẹp về cuối")
        assertEquals(0, TtsProgressMath.equivalentParagraph(-5, 10, 3))
        assertEquals(0, TtsProgressMath.equivalentParagraph(3, 0, 5))
        assertEquals(0, TtsProgressMath.equivalentParagraph(3, 5, 0))
    }
}
