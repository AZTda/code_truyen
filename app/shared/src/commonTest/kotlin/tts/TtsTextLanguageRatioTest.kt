package com.truyencloud.shared.tts

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ngôn ngữ của một đoạn quyết định theo TỈ LỆ ký tự: vài chữ Hán lẻ trong câu Việt (tên chiêu thức, câu thơ) hay một
 * tên riêng có dấu trong câu Anh không được kéo cả đoạn sang giọng khác.
 */
class TtsTextLanguageRatioTest {

    /** Ngữ cảnh chương là trạng thái chung của tiến trình: các bài kiểm thử bộ đọc khác có thể đã để lại. */
    @BeforeTest
    fun clearContext() = TtsTextLanguage.clearContext()

    // region Tiếng Việt xen vài chữ Hán

    @Test
    fun cauVietCoTenChieuThucChuHanVanLaTiengViet() {
        val text = "Hắn thi triển 九阳神功, một chiêu thức vô cùng mạnh mẽ khiến đối thủ phải lùi lại ba bước."
        assertFalse(TtsTextLanguage.isChinese(text))
        assertEquals("vi", TtsTextLanguage.langParam(text))
    }

    @Test
    fun cauVietCoCauThoBonChuVanLaTiengViet() {
        assertEquals("vi", TtsTextLanguage.langParam("Trên tường khắc bốn chữ \"天道酬勤\", nét bút cứng cáp như kiếm."))
    }

    @Test
    fun doanChuHanLaTiengTrungKeCaKhiCoVaiChuLatin() {
        assertEquals("zh", TtsTextLanguage.langParam("山有木兮木有枝，心悦君兮君不知。"))
        assertEquals("zh", TtsTextLanguage.langParam("第一章 重生 (Chapter 1)"))
    }

    @Test
    fun nguongChuHanLaBaMuoiPhanTramChuCai() {
        // 3 chữ Hán / 10 chữ cái = đúng ngưỡng 30% -> tiếng Trung; 2 / 10 -> chưa
        assertTrue(TtsTextLanguage.isChinese("天道酬 abcdefg"))
        assertFalse(TtsTextLanguage.isChinese("天道 abcdefgh"))
    }

    @Test
    fun khongCoChuCaiThiKhongPhaiTiengTrung() {
        assertFalse(TtsTextLanguage.isChinese(""))
        assertFalse(TtsTextLanguage.isChinese("... 123 !?"))
    }

    // endregion

    // region Tiếng Anh xen tên riêng

    @Test
    fun cauAnhCoTenTrungQuocVanLaTiengAnh() {
        assertEquals("en", TtsTextLanguage.langParam("He met 李白 near the river and said nothing."))
    }

    @Test
    fun cauAnhCoTenRiengTiengVietVanLaTiengAnh() {
        assertTrue(TtsTextLanguage.isEnglish("Nguyễn said that the road was long."))
        assertEquals("en", TtsTextLanguage.langParam("Hương said that Nguyễn was late."))
    }

    @Test
    fun cauVietNganCoTuTrungTiengAnhVanLaTiengViet() {
        // 'to', 'no', 'do'... là từ tiếng Việt thật; chỉ có một hai chữ có dấu nên không được đọc bằng giọng Anh
        assertEquals("vi", TtsTextLanguage.langParam("Anh không sao, to lắm."))
        assertEquals("vi", TtsTextLanguage.langParam("Không sao."))
    }

    @Test
    fun vanVietDayDuDauKhongBaoGioLaTiengAnh() {
        assertFalse(TtsTextLanguage.isEnglish("Cô khẽ mỉm cười rồi quay đi."))
        assertFalse(TtsTextLanguage.isEnglish("Nguyễn."))
    }

    @Test
    fun cauAnhThuanVanLaTiengAnh() {
        assertEquals("en", TtsTextLanguage.langParam("She said that the road was long."))
        assertEquals("en", TtsTextLanguage.langParam("Hello world"))
    }

    // endregion

    // region Cả chương chưa dịch

    private val poem = "山有木兮木有枝，心悦君兮君不知。".repeat(15)

    @Test
    fun chuongDichNganCoThoChuHanDaiVanLaDaDich() {
        val chapter = listOf(
            "Nàng khẽ ngâm:",
            poem,
            "Giọng nàng run run, nước mắt chực trào.",
            "Hắn lặng im hồi lâu rồi quay đi."
        )
        // Lỗi cũ: tỉ lệ chữ Hán 78% >= 30% nên bị báo 'chưa có bản dịch' và tự chuyển chương dừng lại
        assertFalse(TtsTextLanguage.isMostlyChinese(chapter))
    }

    @Test
    fun chuongHanTuyetDoiVanLaChuaDichKeCaKhiCoTieuDeLatin() {
        val chapter = listOf("Chương 1", "这是一个没有翻译的段落，讲述了一个很长的故事。".repeat(5))
        assertTrue(TtsTextLanguage.isMostlyChinese(chapter))
    }

    @Test
    fun chuongDaDichDaiCoThoCuoiChuongVanLaDaDich() {
        val chapter = List(40) { "Đây là một đoạn văn tiếng Việt khá dài để mô phỏng một chương truyện dịch hoàn chỉnh, có dấu đầy đủ." } + poem
        assertFalse(TtsTextLanguage.isMostlyChinese(chapter))
    }

    // endregion
}
