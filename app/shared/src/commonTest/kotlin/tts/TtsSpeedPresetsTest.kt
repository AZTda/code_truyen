package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtsSpeedPresetsTest {

    @Test
    fun nutDoiTocDoQuayVongQuaDungCacMocCuaStudio() {
        var s = TtsSpeedPresets.PRESETS.first()
        val visited = mutableListOf(s)
        repeat(TtsSpeedPresets.PRESETS.size) {
            s = TtsSpeedPresets.next(s)
            visited += s
        }
        assertEquals(TtsSpeedPresets.PRESETS + TtsSpeedPresets.PRESETS.first(), visited)
    }

    @Test
    fun khongConMoc08() {
        assertTrue(0.8f !in TtsSpeedPresets.PRESETS)
        assertTrue(0.75f in TtsSpeedPresets.PRESETS)
    }

    @Test
    fun tocDoLeNhayLenMocGanNhat() {
        assertEquals(1.0f, TtsSpeedPresets.next(0.8f))
        assertEquals(1.5f, TtsSpeedPresets.next(1.3f))
        assertEquals(0.75f, TtsSpeedPresets.next(2.5f), "Vượt mốc cuối thì quay về mốc đầu")
    }

    @Test
    fun nhanHienThi() {
        assertEquals("1.0x", TtsSpeedPresets.label(1.0f))
        assertEquals("0.75x", TtsSpeedPresets.label(0.75f))
        assertEquals("1.25x", TtsSpeedPresets.label(1.25f))
        assertEquals("1.5x", TtsSpeedPresets.label(1.5f))
        assertEquals("2.0x", TtsSpeedPresets.label(2.0f))
    }
}
