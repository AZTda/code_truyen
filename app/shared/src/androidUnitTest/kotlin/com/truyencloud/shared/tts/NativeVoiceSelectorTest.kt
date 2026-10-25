package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeVoiceSelectorTest {

    private fun vi(name: String, installed: Boolean = true, network: Boolean = false) =
        NativeVoiceInfo(name, "vi", "VN", installed, network, quality = 300)

    private fun en(name: String, country: String = "US", installed: Boolean = true, network: Boolean = false) =
        NativeVoiceInfo(name, "en", country, installed, network, quality = 300)

    private fun zh(name: String, country: String, language: String = "zh") =
        NativeVoiceInfo(name, language, country, installed = true, networkRequired = false, quality = 300)

    private fun profile(id: String) = DEFAULT_VOICE_PROFILES.first { it.id == id }

    private fun best(voices: List<NativeVoiceInfo>, lang: String, id: String) =
        NativeVoiceSelector.rank(voices, lang, profile(id)).first().name

    // region Tiếng Việt: giới tính

    @Test
    fun maleProfileGetsMaleVoiceWhenItsExactCodeIsMissing() {
        // Bản cũ: không có "vif" -> rơi về giọng đầu danh sách (vic = NỮ) cho hồ sơ NAM
        val voices = listOf(vi("vi-vn-x-vic-local"), vi("vi-vn-x-gft-local"), vi("vi-vn-x-vid-local"))
        assertEquals("vi-vn-x-vid-local", best(voices, "vi", "minh_quang"))
        assertEquals("vi-vn-x-vid-local", best(voices, "vi", "dinh_trong"))
    }

    @Test
    fun femaleProfileGetsFemaleVoiceWhenItsExactCodeIsMissing() {
        val voices = listOf(vi("vi-vn-x-vid-local"), vi("vi-vn-x-vif-local"), vi("vi-vn-x-vic-local"))
        assertEquals("vi-vn-x-vic-local", best(voices, "vi", "thao_vy"))
        assertEquals("vi-vn-x-vic-local", best(voices, "vi", "thu_ha"))
    }

    @Test
    fun exactCodeWinsAmongSameGender() {
        val voices = listOf(vi("vi-vn-x-gft-local"), vi("vi-vn-x-vic-local"), vi("vi-vn-x-vie-local"))
        assertEquals("vi-vn-x-vie-local", best(voices, "vi", "thao_vy"))
        assertEquals("vi-vn-x-vic-local", best(voices, "vi", "mai_phuong"))
        assertEquals("vi-vn-x-gft-local", best(voices, "vi", "thu_ha"))
    }

    @Test
    fun installedVoiceBeatsNotInstalledExactCode() {
        // Bản cũ chọn vif (chưa tải) vì khớp mã giọng, setVoice báo lỗi và bộ máy giữ giọng trước đó
        val voices = listOf(vi("vi-vn-x-vif-local", installed = false), vi("vi-vn-x-vid-local"))
        assertEquals("vi-vn-x-vid-local", best(voices, "vi", "minh_quang"))
    }

    @Test
    fun rightGenderNetworkVoiceBeatsWrongGenderLocalVoice() {
        val voices = listOf(vi("vi-vn-x-vic-local"), vi("vi-vn-x-vif-network", network = true))
        // Hồ sơ nam: giọng nam cần mạng vẫn đứng trước giọng nữ cục bộ (giọng nữ chỉ là dự phòng khi nam lỗi)
        assertEquals(listOf("vi-vn-x-vif-network", "vi-vn-x-vic-local"), NativeVoiceSelector.rank(voices, "vi", profile("minh_quang")).map { it.name })
    }

    @Test
    fun unknownGenderRanksAboveKnownWrongGender() {
        val voices = listOf(vi("vi-vn-x-vic-local"), vi("vi-VN-language"))
        assertEquals("vi-VN-language", best(voices, "vi", "minh_quang"))
    }

    @Test
    fun autoProfileKeepsDefaultPreferenceOrder() {
        val voices = listOf(vi("vi-vn-x-vid-local"), vi("vi-vn-x-gft-local"), vi("vi-vn-x-vic-local"))
        assertEquals("vi-vn-x-vic-local", best(voices, "vi", "google_std"))
    }

    @Test
    fun noVoiceOfTheLanguageGivesEmptyList() {
        assertTrue(NativeVoiceSelector.rank(listOf(en("en-us-x-sfg-local")), "vi", profile("mai_phuong")).isEmpty())
    }

    // endregion

    // region Giới tính theo tên

    @Test
    fun explicitGenderTagOverridesGoogleCode() {
        assertEquals(VoiceGender.MALE, NativeVoiceSelector.genderOf("vi-vn-x-vic#male_1-local"))
        assertEquals(VoiceGender.FEMALE, NativeVoiceSelector.genderOf("vi-vn-x-vid#female_2-local"))
    }

    @Test
    fun googleVietnameseCodes() {
        assertEquals(VoiceGender.FEMALE, NativeVoiceSelector.genderOf("vi-vn-x-vic-local"))
        assertEquals(VoiceGender.FEMALE, NativeVoiceSelector.genderOf("vi-vn-x-vie-network"))
        assertEquals(VoiceGender.FEMALE, NativeVoiceSelector.genderOf("vi-vn-x-gft-local"))
        assertEquals(VoiceGender.MALE, NativeVoiceSelector.genderOf("vi-vn-x-vid-local"))
        assertEquals(VoiceGender.MALE, NativeVoiceSelector.genderOf("vi-vn-x-vif-network"))
    }

    @Test
    fun codesAreMatchedAsWholeTokensNotSubstrings() {
        // Bản cũ dùng contains("vie") nên "vietnamese-voice" bị coi là giọng nữ
        assertEquals(VoiceGender.UNKNOWN, NativeVoiceSelector.genderOf("vietnamese-voice"))
        assertEquals(VoiceGender.UNKNOWN, NativeVoiceSelector.genderOf("en-us-x-tpd-network"))
    }

    @Test
    fun samsungNames() {
        assertEquals(VoiceGender.FEMALE, NativeVoiceSelector.genderOf("vi-VN-SMTf00"))
        assertEquals(VoiceGender.MALE, NativeVoiceSelector.genderOf("en-US-SMTm00"))
    }

    @Test
    fun profileGenderMapping() {
        assertEquals(VoiceGender.MALE, NativeVoiceSelector.wantedGender(profile("hoang_nam")))
        assertEquals(VoiceGender.FEMALE, NativeVoiceSelector.wantedGender(profile("cam_tu")))
        assertEquals(VoiceGender.UNKNOWN, NativeVoiceSelector.wantedGender(profile("google_std")))
        assertEquals(VoiceGender.UNKNOWN, NativeVoiceSelector.wantedGender(null))
    }

    @Test
    fun decomposedFemaleLabelStillMeansFemale() {
        val decomposed = java.text.Normalizer.normalize("Nữ", java.text.Normalizer.Form.NFD)
        assertTrue(decomposed != "Nữ")
        assertEquals(VoiceGender.FEMALE, NativeVoiceSelector.wantedGender(profile("mai_phuong").copy(gender = decomposed)))
    }

    // endregion

    // region Tiếng Anh / Trung

    @Test
    fun maleProfileReadsEnglishWithMaleVoice() {
        // Bản cũ: giọng en đầu tiên trong một Set không thứ tự -> có thể là giọng nữ, hoặc en-GB
        val voices = listOf(
            en("en-gb-x-gba-local", country = "GB"),
            en("en-us-x-sfg#female_1-local"),
            en("en-us-x-iom#male_1-local"),
            en("en-au-x-aua-local", country = "AU")
        )
        assertEquals("en-us-x-iom#male_1-local", best(voices, "en", "minh_quang"))
        assertEquals("en-us-x-sfg#female_1-local", best(voices, "en", "mai_phuong"))
    }

    @Test
    fun englishPrefersUsOverOtherRegionsWhenGenderIsUnknown() {
        val voices = listOf(en("en-gb-x-gba-local", country = "GB"), en("en-us-x-tpd-local"))
        assertEquals("en-us-x-tpd-local", best(voices, "en", "mai_phuong"))
    }

    @Test
    fun englishPrefersLocalOverNetwork() {
        val voices = listOf(en("en-us-x-tpd-network", network = true), en("en-us-x-tpc-local"))
        assertEquals("en-us-x-tpc-local", best(voices, "en", "google_std"))
    }

    @Test
    fun chinesePrefersMainlandMandarinAndDropsCantoneseCode() {
        val voices = listOf(
            zh("cmn-tw-x-ctc-local", "TW"),
            zh("yue-hk-x-yue-local", "HK", language = "yue"),
            zh("cmn-cn-x-ccc-local", "CN")
        )
        val ranked = NativeVoiceSelector.rank(voices, "zh", profile("mai_phuong")).map { it.name }
        assertEquals(listOf("cmn-cn-x-ccc-local", "cmn-tw-x-ctc-local"), ranked)
    }

    @Test
    fun canonicalLanguage() {
        assertEquals("vi", NativeVoiceSelector.canonicalLanguage("vie"))
        assertEquals("vi", NativeVoiceSelector.canonicalLanguage("vi_VN"))
        assertEquals("en", NativeVoiceSelector.canonicalLanguage("EN-us"))
        assertEquals("zh", NativeVoiceSelector.canonicalLanguage("cmn"))
        assertEquals("yue", NativeVoiceSelector.canonicalLanguage("yue"))
    }

    // endregion
}
