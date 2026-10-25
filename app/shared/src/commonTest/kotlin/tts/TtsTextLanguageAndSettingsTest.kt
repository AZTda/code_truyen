package com.truyencloud.shared.tts

import com.truyencloud.shared.data.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TtsTextLanguageAndSettingsTest {

    @Test
    fun nhanDienNgonNgu() {
        assertEquals("zh", TtsTextLanguage.langParam("他说：“你好，世界。”"))
        assertEquals("en", TtsTextLanguage.langParam("She said that the road was long."))
        assertEquals("vi", TtsTextLanguage.langParam("Cô khẽ mỉm cười rồi quay đi."))
    }

    @Test
    fun chuongChuaDichDuocNhanRa() {
        val chinese = List(5) { "这是一个没有翻译的段落，讲述了一个很长的故事。" }
        assertTrue(TtsTextLanguage.isMostlyChinese(chinese))
    }

    @Test
    fun banDichCoVaiChuHanLeKhongBiCoiLaChuaDich() {
        val translated = List(5) { "Lâm Tri Vi (林知微) kéo lại chiếc khăn len quanh cổ rồi bước vào thư viện." }
        assertFalse(TtsTextLanguage.isMostlyChinese(translated))
        assertFalse(TtsTextLanguage.isMostlyChinese(emptyList()))
    }

    @Test
    fun doiCoChuHoacChuDeKhongLamDoiCaiDatTts() {
        val base = UserProfile()
        val before = base.ttsPlaybackSettings()
        val afterFont = base.copy(readerFontSize = 26, preferredTheme = "sepia", readerLineHeight = 2.2f, readerPageMargin = 30)
            .ttsPlaybackSettings()
        assertEquals(before, afterFont, "Đổi cỡ chữ/chủ đề không được tính là đổi cài đặt TTS (distinctUntilChanged sẽ chặn)")
    }

    @Test
    fun doiGiongHoacTocDoLamDoiCaiDatTts() {
        val base = UserProfile()
        assertNotEquals(base.ttsPlaybackSettings(), base.copy(ttsVoiceProfileId = "minh_quang").ttsPlaybackSettings())
        assertNotEquals(base.ttsPlaybackSettings(), base.copy(ttsSpeed = 1.5f).ttsPlaybackSettings())
        assertNotEquals(base.ttsPlaybackSettings(), base.copy(ttsOnlineMode = false).ttsPlaybackSettings())
        assertNotEquals(base.ttsPlaybackSettings(), base.copy(serverUrl = "https://example.org").ttsPlaybackSettings())
    }
}
