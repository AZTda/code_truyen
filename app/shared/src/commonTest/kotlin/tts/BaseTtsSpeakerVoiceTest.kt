package com.truyencloud.shared.tts

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Giọng đọc mà người nghe thực sự nghe: id đến được bộ phát, đổi giọng không đọc lại chữ cũ, mảnh không có âm bị bỏ qua. */
class BaseTtsSpeakerVoiceTest {

    @AfterTest
    fun clearContext() = TtsTextLanguage.clearContext()

    @Test
    fun doiGiongLucDangChoChuongKeKhongDocLaiCauCuoiChuongCu() {
        val s = FakeTtsSpeaker()
        var completed = 0
        s.setOnChapterCompleted { completed++ }
        s.speak(listOf("Câu một.", "Câu cuối chương."), 1)
        s.finish(1)
        assertEquals(1, completed)
        assertTrue(s.isPlaying.value, "Đang giữ phiên chờ chương kế")

        s.setVoiceProfile("minh_quang")
        s.setSpeed(1.5f)
        s.setOnlineMode(false)

        // Lỗi cũ: mỗi lần đổi cài đặt đọc lại câu cuối chương cũ bằng giọng mới rồi báo "hết chương" lần nữa
        assertEquals(listOf("play:1"), s.plays())
        assertEquals(1, completed)

        // Chương kế nạp xong: dùng đúng giọng / tốc độ mới
        s.speak(listOf("Chương mới."), 0)
        assertEquals(listOf("play:1", "play:0"), s.plays())
        assertEquals("minh_quang", s.currentVoiceProfile.value.id)
        assertEquals(1.5f, s.speed.value)
        assertFalse(s.isOnlineMode.value)
    }

    @Test
    fun doiGiongKhiDangDocBinhThuongVanDocLaiNgayManhHienTai() {
        val s = FakeTtsSpeaker()
        s.speak(listOf("a", "b", "c"), 0)
        s.setVoiceProfile("cam_tu")
        assertEquals(listOf("play:0", "play:0"), s.plays())
    }

    @Test
    fun amSacGiongMayNamTramRaGiongNamKhongPhaiGiongNuMacDinh() {
        val s = FakeTtsSpeaker()
        s.setVoiceTone("Nam trầm (Hạ tông)")
        assertEquals("minh_quang", s.currentVoiceProfile.value.id)
        assertEquals("Nam", s.currentVoiceProfile.value.gender)
        s.setVoiceTone("Nữ chuẩn (Hệ thống)")
        assertEquals("mai_phuong", s.currentVoiceProfile.value.id)
        s.setVoiceTone("Mặc định máy")
        assertEquals("google_std", s.currentVoiceProfile.value.id)
    }

    @Test
    fun setVoiceProfileNhanCaTenAmSacVaIdKhongHopLeVeMacDinh() {
        val s = FakeTtsSpeaker()
        s.setVoiceProfile("Trầm ấm")
        assertEquals("minh_quang", s.currentVoiceProfile.value.id)
        s.setVoiceProfile("khong_co_giong_nay")
        // Id lạ: giọng nữ mặc định của danh mục VieNeu đang dùng (Nano: Mỹ Duyên), như máy chủ
        assertEquals("my_duyen", s.currentVoiceProfile.value.id)
    }

    @Test
    fun manhNganCachBiBoQuaKhiDangPhat() {
        val s = FakeTtsSpeaker()
        s.speak(listOf("Câu một.", "***", "◆ ◆ ◆", "Câu hai."), 0)
        s.finish(0)
        // Lỗi cũ: gửi "***" lên máy chủ -> 500 -> bật cầu dao, cả chương còn lại đọc bằng giọng máy
        assertEquals(listOf("play:0", "play:3"), s.plays())
        assertEquals(3, s.currentParagraphIndex.value)
    }

    @Test
    fun batDauTaiManhNganCachThiNhayQuaManhDocDuoc() {
        val s = FakeTtsSpeaker()
        s.speak(listOf("Mở đầu.", "———", "Cảnh mới."), 1)
        assertEquals(2, s.currentParagraphIndex.value)
        assertEquals(listOf("play:2"), s.plays())
    }

    @Test
    fun chuongChiToanNganCachThiHetChuongNgayKhongPhatGi() {
        val s = FakeTtsSpeaker()
        var completed = 0
        s.setOnChapterCompleted { completed++ }
        s.speak(listOf("***", "——"), 0)
        assertTrue(s.plays().isEmpty())
        assertEquals(1, completed)
    }

    @Test
    fun lui1DoanBoQuaNganCach() {
        val s = FakeTtsSpeaker()
        s.speak(listOf("Một.", "***", "Hai.", "Ba."), 3)
        s.prevParagraph()
        s.prevParagraph()
        assertEquals(0, s.currentParagraphIndex.value, "Lùi qua '***' thẳng tới đoạn đọc được")
    }

    @Test
    fun nguCanhNgonNguDuocNapCungVanBanCuaPhienDoc() {
        val s = FakeTtsSpeaker()
        s.speak(listOf("Cô khẽ mỉm cười rồi quay đi.", "Minh.", "12"), 0)
        assertEquals("vi", TtsTextLanguage.langParam("Minh."))
        s.stop()
        s.prepare(listOf("She said that the road was long.", "Sorry.", "12"), 0)
        assertEquals("en", TtsTextLanguage.langParam("Sorry."))
    }
}
