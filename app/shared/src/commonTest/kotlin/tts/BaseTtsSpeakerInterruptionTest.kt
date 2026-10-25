package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Mất / lấy lại quyền phát do hệ thống (cuộc gọi, audio focus, Siri), từ chối quyền phát, lỗi nặng, nhường tiếng. */
class BaseTtsSpeakerInterruptionTest {

    private val paras = listOf("a", "b", "c")

    @Test
    fun matFocusTamThoiThiTuPhatLai() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.interruptionBegan()
        assertFalse(s.isPlaying.value)
        assertTrue("pause:keep=true" in s.log, "Phải giữ focus để nhận lại GAIN")

        s.interruptionEnded(resume = true)
        assertTrue(s.isPlaying.value)
        assertEquals(listOf("play:1"), s.plays(), "Phát lại đúng chỗ, không đọc lại câu")
    }

    @Test
    fun ketThucNgatQuangKhongChoPhepPhatLaiThiOLaiTamDung() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.interruptionBegan()
        s.interruptionEnded(resume = false)
        assertFalse(s.isPlaying.value)
    }

    @Test
    fun nguoiDungBamDungTrongLucBiNgatQuangThiKhongTuPhatLai() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.interruptionBegan()
        s.pause()
        s.interruptionEnded(resume = true)
        assertFalse(s.isPlaying.value, "Người dùng đã chủ động dừng")
    }

    @Test
    fun matQuyenPhatVinhVienThiDungVaKhongTuPhatLai() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.rightsLost()
        assertFalse(s.isPlaying.value)
        s.interruptionEnded(resume = true)
        assertFalse(s.isPlaying.value)
    }

    @Test
    fun ngatQuangKhiDangDungKhongLamGiCa() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.pause()
        s.interruptionBegan()
        s.interruptionEnded(resume = true)
        assertFalse(s.isPlaying.value, "Đang dừng sẵn thì không được tự phát khi hết ngắt quãng")
    }

    @Test
    fun quyenPhatDuocCapSauThiBatDauPhat() {
        val s = FakeTtsSpeaker(PlaybackGrant.DELAYED)
        s.resumable = false // chưa phát gì nên không có gì để "tiếp tục"
        s.speak(paras, 1)
        assertFalse(s.isPlaying.value)
        assertTrue(s.plays().isEmpty())

        s.interruptionEnded(resume = true)
        assertTrue(s.isPlaying.value)
        assertEquals(listOf("play:1"), s.plays())
    }

    @Test
    fun biTuChoiQuyenPhatThiBaoLoi() {
        val s = FakeTtsSpeaker(PlaybackGrant.DENIED)
        s.speak(paras, 0)
        assertFalse(s.isPlaying.value)
        assertNotNull(s.lastError.value)
    }

    @Test
    fun loiNghiemTrongThiDungVaBaoLoi() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.fatal("hỏng")
        assertFalse(s.isPlaying.value)
        assertEquals("hỏng", s.lastError.value)
    }

    @Test
    fun nhuongTiengChiDoiAmLuongKhiThayDoi() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.duck(false)
        assertTrue(s.log.none { it.startsWith("volume:") })
        s.duck(true)
        s.duck(true)
        assertEquals(listOf("volume:0.25"), s.log.filter { it.startsWith("volume:") })
        s.duck(false)
        assertEquals("volume:1.0", s.log.last { it.startsWith("volume:") })
    }
}
