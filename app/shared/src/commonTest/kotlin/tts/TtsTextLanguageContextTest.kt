package com.truyencloud.shared.tts

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Đoạn quá ngắn / không dấu / không có chữ ("Minh.", "Ha ha!", "12") không đủ bằng chứng: theo ngôn ngữ của CHƯƠNG đang
 * đọc. Trước đây mọi chuỗi ASCII đều là tiếng Anh nên tên người và số trong truyện Việt bị giọng Anh (Jenny / Guy) đọc.
 */
class TtsTextLanguageContextTest {

    @BeforeTest
    @AfterTest
    fun clearContext() = TtsTextLanguage.clearContext()

    private val vietnameseChapter = listOf(
        "Cô khẽ mỉm cười rồi quay đi.",
        "Hắn không nói gì thêm, chỉ lặng lẽ nhìn ra cửa sổ.",
        "Minh.",
        "12"
    )
    private val englishChapter = listOf(
        "She said that the road was long.",
        "He did not answer her for a very long time.",
        "Sorry.",
        "12"
    )

    @Test
    fun khongCoChuCaiThiKhongBaoGioLaTiengAnh() {
        // Lỗi cũ: số / dấu câu ASCII -> "en" (Jenny đọc "1998" là "nineteen ninety-eight") còn "——" lại -> "vi"
        for (s in listOf("12", "1998", "100%", "...", "***", "* * *", "10:30", "3.14", "(1)", "——", "◆◆◆")) {
            assertEquals("vi", TtsTextLanguage.langParam(s), "'$s'")
            assertFalse(TtsTextLanguage.isEnglish(s), "'$s'")
        }
    }

    @Test
    fun trongChuongVietTenVaTiengThanKhongDauVanLaTiengViet() {
        TtsTextLanguage.useContextOf(vietnameseChapter)
        for (s in listOf("Minh.", "Lan?", "Anh Hai.", "Ha ha ha!", "Hi hi", "Oa!", "A!", "Viet Nam", "Chuong 1", "12")) {
            assertEquals("vi", TtsTextLanguage.langParam(s), "'$s'")
        }
        assertFalse(TtsTextLanguage.isEnglish("Minh."))
    }

    @Test
    fun tiengVietKhongDauKhongBiDocBangGiongAnh() {
        // Lỗi cũ: 'do' (từ Anh) làm cả câu thành lang=en dù ngữ cảnh là truyện Việt hay không có ngữ cảnh
        for (s in listOf("Toi khong biet nguoi do la ai", "Anh ta khong duoc phep di.", "Nguoi do cung vua cuoi.")) {
            assertEquals("vi", TtsTextLanguage.langParam(s), "'$s'")
        }
        // Câu Anh có một từ trùng ('to') không bị kéo sang tiếng Việt
        assertEquals("en", TtsTextLanguage.langParam("She went to the market with her friend."))
    }

    @Test
    fun trongChuongVietCauAnhRoRangVanLaTiengAnh() {
        TtsTextLanguage.useContextOf(vietnameseChapter)
        assertEquals("en", TtsTextLanguage.langParam("She said that the road was long."))
        assertEquals("en", TtsTextLanguage.langParam("I love you so much"))
        assertEquals("vi", TtsTextLanguage.langParam("Cô khẽ mỉm cười."))
    }

    @Test
    fun trongChuongAnhCauNganVanLaTiengAnhCauVietCoDauVanLaViet() {
        TtsTextLanguage.useContextOf(englishChapter)
        for (s in listOf("Sorry.", "Hello.", "Okay.", "Thanks.", "Wow.", "12", "***")) {
            assertEquals("en", TtsTextLanguage.langParam(s), "'$s'")
        }
        assertEquals("vi", TtsTextLanguage.langParam("Xin chào, bạn khỏe không?"))
    }

    @Test
    fun trongChuongTrungSoDocBangGiongTrungChuLatinLeVanLaAnh() {
        TtsTextLanguage.useContextOf(listOf("山有木兮木有枝，心悦君兮君不知。", "他说：“你好，世界。”"))
        assertEquals("zh", TtsTextLanguage.langParam("12"))
        assertEquals("en", TtsTextLanguage.langParam("OK"))
        assertTrue(TtsTextLanguage.isChinese("2024"))
    }

    @Test
    fun chuaBietNguCanhThiGiuHanhViCu() {
        assertEquals("en", TtsTextLanguage.langParam("Hello world"))
        assertEquals("en", TtsTextLanguage.langParam("Minh."))
        assertEquals("vi", TtsTextLanguage.langParam("Nguyễn."))
    }

    @Test
    fun nguCanhChiDoiKhiNapVanBanMoi() {
        TtsTextLanguage.useContextOf(vietnameseChapter)
        assertEquals("vi", TtsTextLanguage.langParam("Minh."))
        TtsTextLanguage.useContextOf(englishChapter)
        assertEquals("en", TtsTextLanguage.langParam("Minh."))
        TtsTextLanguage.useContextOf(emptyList())
        assertEquals("en", TtsTextLanguage.langParam("Minh."), "Văn bản rỗng -> không có ngữ cảnh -> hành vi cũ")
    }

    @Test
    fun ngonNguChuDaoTinhTheoDoanDuBangChung() {
        assertEquals("vi", TtsTextLanguage.dominantLanguage(vietnameseChapter))
        assertEquals("en", TtsTextLanguage.dominantLanguage(englishChapter))
        assertEquals("zh", TtsTextLanguage.dominantLanguage(listOf("山有木兮木有枝", "他说你好世界")))
        assertNull(TtsTextLanguage.dominantLanguage(listOf("***", "12", "", "Minh.")), "Toàn đoạn mơ hồ -> chưa biết")
        assertNull(TtsTextLanguage.dominantLanguage(emptyList()))
    }

    @Test
    fun hoaThiUuTienTiengViet() {
        assertEquals("vi", TtsTextLanguage.dominantLanguage(listOf("Cô khẽ mỉm cười.", "She said that it was late.")))
    }

    @Test
    fun chuongDaiChiLayMauNhungVanRaDungNgonNgu() {
        val long = List(5_000) { if (it % 7 == 0) "She said that it was late." else "Cô khẽ mỉm cười rồi quay đi." }
        assertEquals("vi", TtsTextLanguage.dominantLanguage(long))
    }

    @Test
    fun urlVaMayChuTienNapDungChungMotNgonNguChoCungMotDoan() {
        // `lang` của yêu cầu phát luồng phải trùng `lang` của yêu cầu tiền nạp, nếu không trượt bộ đệm của máy chủ
        TtsTextLanguage.useContextOf(vietnameseChapter)
        val url = TtsStreamUrl.build("https://h", "Minh.", "Lan?", "mai_phuong")
        assertTrue(url.contains("&lang=vi"))
        assertTrue(url.contains("prefetch_next=Lan%3F"))
    }
}
