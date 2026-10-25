package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepTimerLogicTest {

    @Test
    fun hangGioCoThoiGianBatDauDemKhiDangPhat() {
        val s = SleepTimerLogic.start(SleepTimerMode.MIN_15, isPlaying = true)
        assertEquals(15 * 60, s.remainingSeconds)
        assertTrue(s.isCountingDown)
    }

    @Test
    fun khongDemNguocKhiDangTamDung() {
        val paused = SleepTimerLogic.start(SleepTimerMode.MIN_30, isPlaying = false)
        assertFalse(paused.isCountingDown)
        val after = SleepTimerLogic.tick(SleepTimerLogic.tick(paused))
        assertEquals(30 * 60, after.remainingSeconds, "Đang dừng thì đồng hồ không được chạy")
    }

    @Test
    fun tamDungRoiTiepTucGiuNguyenThoiGianConLai() {
        var s = SleepTimerLogic.start(SleepTimerMode.MIN_15, isPlaying = true)
        repeat(10) { s = SleepTimerLogic.tick(s) }
        assertEquals(15 * 60 - 10, s.remainingSeconds)

        s = SleepTimerLogic.onPlaybackChanged(s, isPlaying = false)
        repeat(100) { s = SleepTimerLogic.tick(s) }
        assertEquals(15 * 60 - 10, s.remainingSeconds, "Trong lúc dừng không được trừ giờ")

        s = SleepTimerLogic.onPlaybackChanged(s, isPlaying = true)
        s = SleepTimerLogic.tick(s)
        assertEquals(15 * 60 - 11, s.remainingSeconds)
    }

    @Test
    fun hetGioThiDanhDauHetHan() {
        var s = SleepTimerState(SleepTimerMode.MIN_15, remainingSeconds = 2, isCountingDown = true)
        s = SleepTimerLogic.tick(s)
        assertFalse(s.isExpired)
        s = SleepTimerLogic.tick(s)
        assertTrue(s.isExpired)
        assertEquals(0, SleepTimerLogic.tick(s).remainingSeconds, "Không được xuống số âm")
    }

    @Test
    fun amLuongGiamDanTrong15GiayCuoi() {
        fun factor(remaining: Int) = SleepTimerLogic.fadeFactor(
            SleepTimerState(SleepTimerMode.MIN_15, remaining, isCountingDown = true)
        )
        assertEquals(1.0f, factor(600))
        assertEquals(1.0f, factor(SleepTimerLogic.FADE_SECONDS))
        assertTrue(factor(8) < 1.0f && factor(8) > factor(3), "Càng gần hết càng nhỏ")
        assertEquals(0.05f, factor(0), "Có sàn nhỏ nhất, không tắt hẳn đột ngột")
    }

    @Test
    fun hetChuongDungHanVaTuTatHenGio() {
        val s = SleepTimerLogic.start(SleepTimerMode.END_OF_CHAPTER, isPlaying = true)
        assertNull(s.remainingSeconds)
        val (next, stop) = SleepTimerLogic.onChapterCompleted(s)
        assertTrue(stop, "Hết chương phải DỪNG, không được sang chương sau")
        assertEquals(SleepTimerMode.OFF, next.mode)
    }

    @Test
    fun henGioTheoPhutKhongDungKhiHetChuong() {
        val s = SleepTimerLogic.start(SleepTimerMode.MIN_30, isPlaying = true)
        val (next, stop) = SleepTimerLogic.onChapterCompleted(s)
        assertFalse(stop)
        assertEquals(s, next)
    }

    @Test
    fun tatHenGio() {
        val s = SleepTimerLogic.start(SleepTimerMode.OFF, isPlaying = true)
        assertEquals(SleepTimerState(), s)
        assertEquals("", SleepTimerLogic.label(s))
    }

    @Test
    fun nhanHienThi() {
        assertEquals("Tắt khi hết chương", SleepTimerLogic.label(SleepTimerState(SleepTimerMode.END_OF_CHAPTER)))
        assertEquals(
            "Tắt sau 01:05",
            SleepTimerLogic.label(SleepTimerState(SleepTimerMode.MIN_15, 65, isCountingDown = true))
        )
        assertEquals(
            "Tắt sau 01:05 (tạm dừng)",
            SleepTimerLogic.label(SleepTimerState(SleepTimerMode.MIN_15, 65, isCountingDown = false))
        )
    }
}
