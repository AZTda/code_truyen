package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Máy trạng thái phiên đọc: phát, chuyển mảnh, hết chương, hẹn giờ, setter. */
class BaseTtsSpeakerTest {

    private val paras = listOf("a", "b", "c")

    @Test
    fun speakPhatDungManhYeuCau() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        assertTrue(s.isPlaying.value)
        assertEquals(1, s.currentParagraphIndex.value)
        assertEquals(listOf("play:1"), s.plays())
    }

    @Test
    fun chiSoBatDauNgoaiKhoangDuocKep() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 99)
        assertEquals(2, s.currentParagraphIndex.value)
    }

    @Test
    fun boQuaManhTrongKhiBatDau() {
        val s = FakeTtsSpeaker()
        s.speak(listOf("", "  ", "x"), 0)
        assertEquals(2, s.currentParagraphIndex.value)
        assertEquals(listOf("play:2"), s.plays())
    }

    @Test
    fun danhSachRongThiDung() {
        val s = FakeTtsSpeaker()
        s.speak(emptyList(), 0)
        assertFalse(s.isPlaying.value)
        assertTrue("stop" in s.log)
    }

    @Test
    fun docXongMotManhThiChuyenSangManhKe() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.finish(0)
        s.finish(1)
        assertEquals(listOf("play:0", "play:1", "play:2"), s.plays())
        assertEquals(2, s.currentParagraphIndex.value)
    }

    @Test
    fun suKienCuCuaManhDaBiThayBiBoQua() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.finish(0)
        assertEquals(listOf("play:1"), s.plays())
        assertEquals(1, s.currentParagraphIndex.value)
    }

    @Test
    fun suKienCuSauKhiDungKhongLamPhienSongLai() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.pause()
        s.finish(0)
        assertFalse(s.isPlaying.value)
        assertEquals(listOf("play:0"), s.plays())
    }

    @Test
    fun hetChuongBaoDungMotLanVaGiuPhien() {
        val s = FakeTtsSpeaker()
        var completed = 0
        s.setOnChapterCompleted { completed++ }
        s.speak(paras, 2)
        s.finish(2)
        assertEquals(1, completed)
        assertTrue(s.isPlaying.value, "Giữ phiên trong lúc tải chương kế")
    }

    @Test
    fun hetChuongKhongCoNguoiNgheThiTamDung() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 2)
        s.finish(2)
        assertFalse(s.isPlaying.value)
    }

    @Test
    fun henGioHetChuongDungVaKhongBaoHoanTat() {
        val s = FakeTtsSpeaker()
        var completed = 0
        s.setOnChapterCompleted { completed++ }
        s.speak(paras, 2)
        s.setSleepTimer(SleepTimerMode.END_OF_CHAPTER)
        assertEquals(SleepTimerMode.END_OF_CHAPTER, s.sleepTimerMode.value)

        s.finish(2)

        assertEquals(0, completed, "Hết chương: không được sang chương sau")
        assertFalse(s.isPlaying.value)
        assertEquals(SleepTimerMode.OFF, s.sleepTimerMode.value, "Hẹn giờ tự tắt sau khi dừng")
    }

    @Test
    fun sauKhiHenGioHetChuongDaTatLanSauBaoHoanTatBinhThuong() {
        val s = FakeTtsSpeaker()
        var completed = 0
        s.setOnChapterCompleted { completed++ }
        s.speak(paras, 2)
        s.setSleepTimer(SleepTimerMode.END_OF_CHAPTER)
        s.finish(2)
        s.resume()
        s.finish(2)
        assertEquals(1, completed)
    }

    @Test
    fun henGioBiHuyKhiDung() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.setSleepTimer(SleepTimerMode.END_OF_CHAPTER)
        s.stop()
        assertEquals(SleepTimerMode.OFF, s.sleepTimerMode.value)
    }

    @Test
    fun settersKhongDoiGiaTriThiKhongLamGiCa() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        val before = s.log.toList()

        s.setSpeed(1.0f)
        s.setVoiceProfile(s.currentVoiceProfile.value.id)
        s.setOnlineMode(true)
        s.setBackgroundPlayback(true)

        assertEquals(before, s.log, "Đẩy lại cài đặt y hệt không được đọc lại câu đang đọc")
    }

    @Test
    fun doiGiongKhiDangPhatThiDocLaiManhHienTaiVoiGiongMoi() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.setVoiceProfile("minh_quang")
        assertEquals("minh_quang", s.currentVoiceProfile.value.id)
        assertEquals(listOf("play:1", "play:1"), s.plays())
        assertTrue("voice:minh_quang" in s.log)
    }

    @Test
    fun doiTocDoDuocKepVaDocLai() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.setSpeed(5f)
        assertEquals(2.0f, s.speed.value)
        assertEquals(2, s.plays().size)
    }

    @Test
    fun doiCaiDatKhiDangDungThiKhongTuPhat() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.pause()
        s.setSpeed(1.5f)
        s.setVoiceProfile("thao_vy")
        assertEquals(1, s.plays().size)
        assertFalse(s.isPlaying.value)
    }

    @Test
    fun tamDungRoiTiepTucDungChoKhongDocLai() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.pause()
        s.resume()
        assertTrue(s.isPlaying.value)
        assertEquals(listOf("play:1"), s.plays())
        assertTrue("resume" in s.log)
    }

    @Test
    fun khongTiepTucDuocThiDocLaiManhHienTai() {
        val s = FakeTtsSpeaker()
        s.resumable = false
        s.speak(paras, 1)
        s.pause()
        s.resume()
        assertEquals(listOf("play:1", "play:1"), s.plays())
    }

    @Test
    fun phatLaiXoaLoiCu() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.fatal("hỏng")
        s.resume()
        assertNull(s.lastError.value)
    }

    @Test
    fun nextOCuoiChuongThiBaoHoanTat() {
        val s = FakeTtsSpeaker()
        var completed = 0
        s.setOnChapterCompleted { completed++ }
        s.speak(paras, 2)
        s.nextParagraph()
        assertEquals(1, completed)
    }

    @Test
    fun prevBoQuaMucTrong() {
        val s = FakeTtsSpeaker()
        s.speak(listOf("a", "", "c"), 2)
        s.prevParagraph()
        assertEquals(0, s.currentParagraphIndex.value)
    }

    @Test
    fun nextKhiDangDungChiDoiViTri() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.pause()
        s.nextParagraph()
        assertEquals(1, s.currentParagraphIndex.value)
        assertEquals(1, s.plays().size)
    }

    @Test
    fun dungGiuNguyenViTri() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 2)
        s.stop()
        assertFalse(s.isPlaying.value)
        assertEquals(2, s.currentParagraphIndex.value)
        assertTrue("stop" in s.log)
    }
}
