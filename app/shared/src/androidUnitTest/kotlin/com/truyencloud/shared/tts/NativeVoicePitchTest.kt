package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hồ sơ nam trên máy chỉ có giọng nữ: phải nghe trầm hơn hồ sơ nữ (như iOS), không được y hệt giọng nữ. */
class NativeVoicePitchTest {

    private fun profile(id: String) = DEFAULT_VOICE_PROFILES.first { it.id == id }

    @Test
    fun maleProfileOnKnownFemaleVoiceIsLowered() {
        for (id in listOf("minh_quang", "dinh_trong", "bao_long", "hoang_nam")) {
            val p = profile(id)
            val pitch = NativeVoiceSelector.pitchFor(p, "vi", "vi-vn-x-vic-local")
            assertTrue(pitch < 0.8f, "$id: $pitch phải hạ rõ so với ${p.pitch}")
        }
        // Đình Trọng vẫn trầm hơn Hoàng Nam
        assertTrue(
            NativeVoiceSelector.pitchFor(profile("dinh_trong"), "vi", "vi-vn-x-vic-local") <
                NativeVoiceSelector.pitchFor(profile("hoang_nam"), "vi", "vi-vn-x-vic-local")
        )
    }

    @Test
    fun maleProfileOnMaleOrUnknownVoiceKeepsProfilePitch() {
        val p = profile("dinh_trong")
        assertEquals(p.pitch, NativeVoiceSelector.pitchFor(p, "vi", "vi-vn-x-vid-local"))
        assertEquals(p.pitch, NativeVoiceSelector.pitchFor(p, "vi", "vi-vn-x-abc-local"), "Chưa rõ giới tính: không đoán")
        assertEquals(p.pitch, NativeVoiceSelector.pitchFor(p, "vi", null))
    }

    @Test
    fun femaleAndDefaultProfilesAreNeverLowered() {
        for (id in listOf("mai_phuong", "cam_tu", "thu_ha", "google_std")) {
            val p = profile(id)
            assertEquals(p.pitch, NativeVoiceSelector.pitchFor(p, "vi", "vi-vn-x-vic-local"), id)
        }
    }

    @Test
    fun foreignLanguagesUseStandardPitchLikeTheServer() {
        val p = profile("minh_quang")
        assertEquals(1.0f, NativeVoiceSelector.pitchFor(p, "en", "en-us-x-tpf-local"))
        assertEquals(1.0f, NativeVoiceSelector.pitchFor(p, "zh", "cmn-cn-x-ccc-local"))
        assertEquals(1.0f, NativeVoiceSelector.pitchFor(null, "vi", "vi-vn-x-vic-local"))
    }
}
