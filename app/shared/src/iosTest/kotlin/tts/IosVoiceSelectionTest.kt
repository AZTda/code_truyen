@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.truyencloud.shared.tts

import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesisVoiceGender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Chọn giọng máy iOS: giới tính, vùng, chất lượng, thang tốc độ. Phần cuối chạy với giọng thật của máy / simulator. */
class IosVoiceSelectionTest {

    private fun voice(
        id: String,
        lang: String,
        gender: IosVoiceGender,
        quality: Long = 1L
    ) = IosVoiceInfo(id, lang, quality, gender)

    private val female = voice("com.apple.voice.compact.en-US.Samantha", "en-US", IosVoiceGender.FEMALE)
    private val male = voice("com.apple.voice.compact.en-GB.Daniel", "en-GB", IosVoiceGender.MALE)

    private fun profile(id: String) = DEFAULT_VOICE_PROFILES.first { it.id == id }

    @Test
    fun enumGioiTinhCuaAppleDuocDoiDungVaGiongNuKhongThanhNam() {
        // Bản cũ so `gender.toString().contains("male")`: "AVSpeechSynthesisVoiceGenderFemale" cũng chứa "male"
        assertEquals(IosVoiceGender.FEMALE, IosTtsVoiceMatcher.genderOf(AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderFemale))
        assertEquals(IosVoiceGender.MALE, IosTtsVoiceMatcher.genderOf(AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderMale))
        assertEquals(IosVoiceGender.UNSPECIFIED, IosTtsVoiceMatcher.genderOf(AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderUnspecified))
    }

    @Test
    fun hoSoNamDocTiengAnhBangGiongNamVaHoSoNuBangGiongNu() {
        val voices = listOf(female, male)
        assertEquals(male, IosVoiceSelection.pick(voices, IosSpeechLanguage.ENGLISH, wantMale = true))
        assertEquals(female, IosVoiceSelection.pick(voices, IosSpeechLanguage.ENGLISH, wantMale = false))
        // Thứ tự danh sách không đổi kết quả
        assertEquals(male, IosVoiceSelection.pick(voices.reversed(), IosSpeechLanguage.ENGLISH, wantMale = true))
        assertEquals(female, IosVoiceSelection.pick(voices.reversed(), IosSpeechLanguage.ENGLISH, wantMale = false))
    }

    @Test
    fun tiengTrungUuTienGiongPhoThongGianTheKhongPhaiQuangDongHayDaiLoan() {
        val hk = voice("com.apple.voice.compact.zh-HK.Sinji", "zh-HK", IosVoiceGender.FEMALE)
        val tw = voice("com.apple.voice.compact.zh-TW.Meijia", "zh-TW", IosVoiceGender.FEMALE)
        val cn = voice("com.apple.voice.compact.zh-CN.Tingting", "zh-CN", IosVoiceGender.FEMALE)
        listOf(listOf(hk, tw, cn), listOf(tw, hk, cn), listOf(cn, hk, tw)).forEach {
            assertEquals(cn, IosVoiceSelection.pick(it, IosSpeechLanguage.CHINESE, wantMale = false))
            // Chỉ có giọng nữ: hồ sơ nam vẫn đọc bằng giọng tiếng phổ thông (cao độ được hạ ở bước sau)
            assertEquals(cn, IosVoiceSelection.pick(it, IosSpeechLanguage.CHINESE, wantMale = true))
        }
    }

    @Test
    fun giongVuiNhonVaEloquenceChiDungKhiKhongCoGiongKhac() {
        val novelty = voice("com.apple.speech.synthesis.voice.Albert", "en-US", IosVoiceGender.UNSPECIFIED)
        val eloquence = voice("com.apple.eloquence.en-US.Rocko", "en-US", IosVoiceGender.UNSPECIFIED)
        assertEquals(female, IosVoiceSelection.pick(listOf(novelty, eloquence, female), IosSpeechLanguage.ENGLISH, wantMale = false))
        assertEquals(eloquence, IosVoiceSelection.pick(listOf(novelty, eloquence), IosSpeechLanguage.ENGLISH, wantMale = false))
    }

    @Test
    fun goiNangCaoDuocChonHonGoiCompact() {
        val compact = voice("com.apple.voice.compact.vi-VN.Linh", "vi-VN", IosVoiceGender.FEMALE, quality = 1L)
        val enhanced = voice("com.apple.voice.enhanced.vi-VN.Linh", "vi-VN", IosVoiceGender.FEMALE, quality = 2L)
        assertEquals(enhanced, IosVoiceSelection.pick(listOf(compact, enhanced), IosSpeechLanguage.VIETNAMESE, wantMale = false))
        assertEquals(enhanced, IosVoiceSelection.pick(listOf(enhanced, compact), IosSpeechLanguage.VIETNAMESE, wantMale = false))
    }

    @Test
    fun khongCoGiongTiengVietThiKhongChonGiongNgoai() {
        assertNull(IosVoiceSelection.pick(listOf(female, male), IosSpeechLanguage.VIETNAMESE, wantMale = false))
        assertFalse(IosVoiceSelection.matchesLanguage("vie-XX", IosSpeechLanguage.VIETNAMESE))
        assertTrue(IosVoiceSelection.matchesLanguage("vi-VN", IosSpeechLanguage.VIETNAMESE))
        assertTrue(IosVoiceSelection.matchesLanguage("vi_VN", IosSpeechLanguage.VIETNAMESE))
    }

    @Test
    fun ngonNguGiongMayLuonKhopThamSoLangGuiMayChu() {
        listOf("Xin chào bạn", "Hello there, the man said", "你好吗", "* * *", "Minh", "").forEach { text ->
            val expected = when (TtsTextLanguage.langParam(text)) {
                "zh" -> IosSpeechLanguage.CHINESE
                "en" -> IosSpeechLanguage.ENGLISH
                else -> IosSpeechLanguage.VIETNAMESE
            }
            assertEquals(expected, IosVoiceSelection.languageOf(text), "text=$text")
        }
    }

    @Test
    fun thangTocDoCuaAppleKhongTuyenTinh() {
        assertEquals(0.5f, IosVoiceSelection.utteranceRate(1.0f))
        // Công thức cũ `0.5 * tốc độ`: 1.5x -> 0.75 (nghe ~2.5x), 2.0x -> 1.0 (nghe ~4x)
        assertTrue(IosVoiceSelection.utteranceRate(1.5f) in 0.55f..0.62f, "1.5x -> ${IosVoiceSelection.utteranceRate(1.5f)}")
        assertTrue(IosVoiceSelection.utteranceRate(2.0f) in 0.65f..0.72f, "2.0x -> ${IosVoiceSelection.utteranceRate(2.0f)}")
        assertTrue(IosVoiceSelection.utteranceRate(0.5f) < 0.05f)
        // Ngoài khoảng 0.5x-2.0x thì kẹp lại
        assertEquals(IosVoiceSelection.utteranceRate(2.0f), IosVoiceSelection.utteranceRate(9f))
        assertEquals(IosVoiceSelection.utteranceRate(0.5f), IosVoiceSelection.utteranceRate(0.1f))
        // Chậm hơn thì rate không bao giờ cao hơn
        var previous = -1f
        var speed = 0.5f
        while (speed <= 2.0f) {
            val rate = IosVoiceSelection.utteranceRate(speed)
            assertTrue(rate >= previous, "speed=$speed rate=$rate previous=$previous")
            assertTrue(rate in 0f..1f)
            previous = rate
            speed += 0.05f
        }
    }

    @Test
    fun caoDoHoSoNamDuocHaKhiGiongKhongPhaiNamThat() {
        val vi = IosSpeechLanguage.VIETNAMESE
        val minhQuang = profile("minh_quang")
        // Giọng Linh là nữ: hồ sơ nam phải được hạ cao độ (trước đây không bao giờ vì giọng nữ bị coi là nam)
        assertTrue(IosVoiceSelection.pitch(minhQuang, vi, IosVoiceGender.FEMALE) < 0.8f)
        assertTrue(IosVoiceSelection.pitch(minhQuang, vi, null) < 0.8f)
        assertEquals(minhQuang.pitch, IosVoiceSelection.pitch(minhQuang, vi, IosVoiceGender.MALE))
        assertEquals(1.0f, IosVoiceSelection.pitch(minhQuang, IosSpeechLanguage.ENGLISH, IosVoiceGender.FEMALE))
        assertEquals(1.0f, IosVoiceSelection.pitch(profile("mai_phuong"), vi, IosVoiceGender.FEMALE))
        assertTrue(IosVoiceSelection.pitch(profile("cam_tu"), vi, IosVoiceGender.FEMALE) > 1.0f)
    }

    @Test
    fun hoSoNamVaNuTrenMayDungChungMotDuongDungCauDoc() {
        // Phiên đọc và nghe thử cùng gọi buildUtterance: kiểm với giọng thật của máy / simulator (không có thì bỏ qua)
        val speed = 1.5f
        val nam = IosTtsVoiceMatcher.buildUtterance("Hôm nay trời đẹp lắm.", profile("minh_quang"), speed)
        val nu = IosTtsVoiceMatcher.buildUtterance("Hôm nay trời đẹp lắm.", profile("mai_phuong"), speed)
        if (!IosTtsVoiceMatcher.hasVietnameseVoice()) {
            assertNull(nam)
            assertNull(nu)
            return
        }
        assertNotNull(nam)
        assertNotNull(nu)
        assertTrue(nam.voice!!.language.startsWith("vi", ignoreCase = true))
        assertEquals(IosVoiceSelection.utteranceRate(speed), nam.rate)
        assertEquals(nam.rate, nu.rate)
        assertTrue(nam.pitchMultiplier <= nu.pitchMultiplier)
    }

    @Test
    fun docTiengTrungUuTienGiongZhCnKhiMayCo() {
        val installed = AVSpeechSynthesisVoice.speechVoices().filterIsInstance<AVSpeechSynthesisVoice>()
        val hasStandardCn = installed.any {
            it.language.equals("zh-CN", ignoreCase = true) &&
                !it.identifier.startsWith("com.apple.eloquence.") &&
                !it.identifier.startsWith("com.apple.speech.synthesis.voice.")
        }
        val picked = IosTtsVoiceMatcher.bestVoice(IosSpeechLanguage.CHINESE, wantMale = false) ?: return
        if (hasStandardCn) assertEquals("zh-CN", picked.language)
    }
}
