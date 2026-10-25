package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtsVolumeMixerTest {

    private val calls = mutableListOf<Pair<Float, Float>>()
    private val mixer = TtsVolumeMixer { fade, duck -> calls += fade to duck }

    @Test
    fun chiDayXuongBoPhatKhiNhuongTiengThayDoi() {
        mixer.setDucked(false)
        assertTrue(calls.isEmpty())
        mixer.setDucked(true)
        mixer.setDucked(true)
        assertEquals(listOf(1.0f to TtsVolumeMixer.DUCK_FACTOR), calls)
    }

    @Test
    fun henGioNguVaNhuongTiengNhanVoiNhau() {
        mixer.setFade(0.5f)
        mixer.setDucked(true)
        assertEquals(0.5f to TtsVolumeMixer.DUCK_FACTOR, calls.last())
        assertEquals(0.5f, mixer.fade)
        assertEquals(TtsVolumeMixer.DUCK_FACTOR, mixer.duck)
    }

    @Test
    fun resetDuaCaHaiHeSoVeMotVaDayMucDayXuongBoPhat() {
        mixer.setFade(0.5f)
        mixer.setDucked(true)
        calls.clear()
        mixer.reset()
        // Lỗi cũ: hệ số về 1.0 nhưng bộ phát (Android) vẫn nhớ mức cũ cho phiên sau
        assertEquals(listOf(1.0f to 1.0f), calls)
        assertEquals(1.0f, mixer.fade)
        assertEquals(1.0f, mixer.duck)
    }

    @Test
    fun resetKhiDangOMucDayKhongDayGiCa() {
        mixer.reset()
        assertTrue(calls.isEmpty())
    }
}
