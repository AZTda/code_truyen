package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Nạp văn bản của phiên mới lúc đang tạm dừng (chuyển chương), và hệ số âm lượng (nhường tiếng) không được dính sang
 * phiên sau.
 */
class BaseTtsSpeakerPrepareVolumeTest {

    private val paras = listOf("a", "b", "c", "d", "e")

    // region Nạp văn bản mới (prepare)

    @Test
    fun napVanBanMoiLucTamDungKhongTuPhatVaTiepTucDocVanBanMoi() {
        val s = FakeTtsSpeaker()
        s.speak(listOf("cu0", "cu1", "cu2"), 2)
        s.pause()

        s.prepare(listOf("moi0", "moi1"), 0)

        assertFalse(s.isPlaying.value, "Chương mới được nạp trong lúc người dùng đang tạm dừng: không được tự phát")
        assertEquals(listOf("cu2"), s.spoken)
        assertEquals(0, s.currentParagraphIndex.value)
        s.resume()   // nút Phát ở thông báo / tai nghe cũng đi đường này
        assertEquals(listOf("cu2", "moi0"), s.spoken, "Phát phải đọc chữ MỚI từ đầu, không quay lại đoạn cuối chương cũ")
    }

    @Test
    fun napVanBanMoiKhiDangPhatThiPhatNgayNhuSpeak() {
        val s = FakeTtsSpeaker()
        s.speak(listOf("cu0", "cu1"), 0)
        s.prepare(listOf("moi0", "moi1"), 1)
        assertTrue(s.isPlaying.value)
        assertEquals(listOf("cu0", "moi1"), s.spoken)
    }

    @Test
    fun napVanBanMoiGiuNguyenHenGioNguDangTamDung() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.setSleepTimer(SleepTimerMode.MIN_30)
        s.pause()
        s.prepare(listOf("moi"), 0)
        assertEquals(SleepTimerMode.MIN_30, s.sleepTimerMode.value, "Chỉ nạp văn bản, không được huỷ hẹn giờ của người dùng")
    }

    @Test
    fun napVanBanMoiXoaLoiCuVaBaoTrangThaiChoNenTang() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.fatal("hỏng")
        s.prepare(listOf("moi"), 0)
        assertNull(s.lastError.value)
        assertFalse(s.stateLog.last())
    }

    // endregion

    // region Nhường tiếng (duck) không được dính sang phiên sau

    @Test
    fun dungHanKhiDangBiNhuongTiengThiPhienSauPhatDuAm() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.duck(true)
        s.stop()
        // Lỗi cũ: hệ số được đặt lại nhưng bộ phát vẫn nhớ mức 25%, và lần xin focus sau không có GAIN nào báo về
        assertEquals("volume:1.0", s.volumes().last())
        s.speak(paras, 0)
        s.duck(false)
        assertEquals("volume:1.0", s.volumes().last())
    }

    @Test
    fun matQuyenPhatVinhVienKhiDangNhuongTiengThiTraAmLuong() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.duck(true)
        s.rightsLost()
        assertEquals("volume:1.0", s.volumes().last())
        s.resume()
        assertEquals(listOf("volume:0.25", "volume:1.0"), s.volumes())
    }

    @Test
    fun xinLaiQuyenPhatThanhCongThiHetBiNhuongTiengDuKhongCoGain() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.duck(true)
        s.pause()
        s.resume()   // acquire GRANTED: không có callback GAIN nào báo về
        assertEquals("volume:1.0", s.volumes().last())
    }

    @Test
    fun dungKhiKhongBiNhuongTiengKhongDayAmLuongThua() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.stop()
        s.speak(paras, 0)
        s.rightsLost()
        s.resume()
        assertTrue(s.volumes().isEmpty(), "Không có hệ số nào đổi thì không đẩy âm lượng xuống bộ phát: ${s.volumes()}")
    }

    // endregion
}
