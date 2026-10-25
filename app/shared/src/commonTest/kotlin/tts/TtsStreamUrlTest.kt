package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TtsStreamUrlTest {

    @Test
    fun kyTuDacBietTrongQueryDuocMaHoaDayDu() {
        // `&` và `+` mà không mã hoá sẽ cắt cụt / làm sai đoạn văn
        assertEquals("a%20b%26c%2Bd%2Fe%3Df", TtsStreamUrl.encodeComponent("a b&c+d/e=f"))
        assertEquals("ABCxyz019-._~", TtsStreamUrl.encodeComponent("ABCxyz019-._~"))
    }

    @Test
    fun tiengVietDuocMaHoaTheoUtf8() {
        assertEquals("%C4%91%E1%BB%99c", TtsStreamUrl.encodeComponent("độc"))
        assertEquals("%E4%BD%A0%E5%A5%BD", TtsStreamUrl.encodeComponent("你好"))
    }

    @Test
    fun urlDayDuThamSo() {
        val url = TtsStreamUrl.build("https://h.example/", "Xin chào & bạn", "Câu sau", "mai_phuong")
        assertTrue(url.startsWith("https://h.example/v1/tts/stream?text=Xin%20ch%C3%A0o%20%26%20b%E1%BA%A1n"))
        assertTrue(url.contains("&voice=mai_phuong"))
        assertTrue(url.contains("&speed=1.0&"), "Tốc độ do trình phát áp: URL không đổi theo tốc độ")
        assertTrue(url.contains("&lang=vi"))
        assertTrue(url.contains("&prefetch_next=C%C3%A2u%20sau"))
    }

    @Test
    fun khongCoGoiYThiBoThamSoPrefetch() {
        assertFalse(TtsStreamUrl.build("https://h", "abc", null, "v").contains("prefetch_next"))
        assertFalse(TtsStreamUrl.build("https://h", "abc", "  ", "v").contains("prefetch_next"))
    }

    @Test
    fun ngonNguTruyenDungTheoVanBan() {
        assertTrue(TtsStreamUrl.build("https://h", "你好世界", null, "v").contains("&lang=zh"))
        assertTrue(TtsStreamUrl.build("https://h", "She said that it was late.", null, "v").contains("&lang=en"))
    }

    @Test
    fun doanKeKhacNgonNguThiBoGoiYPrefetch() {
        // Máy chủ làm ấm đoạn kế bằng lang của đoạn này; khác ngôn ngữ thì làm ấm sai giọng, tốn công vô ích
        val url = TtsStreamUrl.build("https://h", "Xin chào bạn.", "你好世界，今天天气很好。", "v")
        assertTrue(url.contains("&lang=vi"))
        assertFalse(url.contains("prefetch_next"))
    }

    @Test
    fun goiYTienNapQuaDaiThiBoDeUrlKhongVuotHanMucMayChu() {
        // 1.500 chữ có dấu ~ 9 ký tự mã hoá mỗi chữ: riêng gợi ý đã > 12.000 ký tự, cộng đoạn chính vượt hạn 16 KB của
        // Cloudflare / uvicorn -> yêu cầu bị từ chối -> app tưởng giọng AI hỏng và chuyển sang giọng máy
        val longNext = "ệ".repeat(1500)
        val url = TtsStreamUrl.build("https://h", "Xin chào bạn.", longNext, "mai_phuong")
        assertFalse(url.contains("prefetch_next"))
        assertTrue(url.length <= TtsStreamUrl.MAX_URL_LENGTH)
        assertTrue(url.contains("&voice=mai_phuong"), "Đoạn chính vẫn đủ tham số")
        // Gợi ý vừa phải thì vẫn giữ
        assertTrue(TtsStreamUrl.build("https://h", "Xin chào bạn.", "Câu sau.", "mai_phuong").contains("prefetch_next"))
    }

    @Test
    fun doanVietXenVaiChuHanVanDocBangLangViVaGiuPrefetch() {
        val url = TtsStreamUrl.build("https://h", "Hắn thi triển 九阳神功 rất mạnh.", "Câu sau vẫn là tiếng Việt.", "v")
        assertTrue(url.contains("&lang=vi"), "Lỗi cũ: một chữ Hán làm cả đoạn thành lang=zh")
        assertTrue(url.contains("prefetch_next"))
    }
}
