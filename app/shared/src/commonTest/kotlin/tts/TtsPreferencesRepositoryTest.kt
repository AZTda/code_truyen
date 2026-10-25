package com.truyencloud.shared.tts

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.UserProfile
import com.truyencloud.shared.repo.UserRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ghi giọng vào hồ sơ: không được âm thầm đặt lại về "mai_phuong", không bao giờ lưu id không tồn tại, và tên hiển thị luôn
 * khớp id (nếu không Cài đặt đánh dấu hai giọng cùng lúc / hiện sai giọng đang dùng).
 */
class TtsPreferencesRepositoryTest {

    /** UserRepository (kho = null: chỉ trong RAM) đụng vào trạng thái chung của TruyenCloudApi: trả lại như cũ. */
    private fun withRepo(block: (UserRepository) -> Unit) {
        val baseUrl = TruyenCloudApi.baseUrl
        val token = TruyenCloudApi.authToken
        val onExpired = TruyenCloudApi.onSessionExpired
        try {
            block(UserRepository())
        } finally {
            TruyenCloudApi.baseUrl = baseUrl
            TruyenCloudApi.authToken = token
            TruyenCloudApi.onSessionExpired = onExpired
        }
    }

    @Test
    fun hoSoMacDinhCoCapGiongKhop() {
        val p = UserProfile()
        // Cài đặt mới: giọng VieNeu nữ mặc định (Mỹ Duyên), tên khớp id
        val voice = TtsVoiceResolver.byId(p.ttsVoiceProfileId)
        assertEquals("my_duyen", voice?.id)
        assertEquals(voice?.name, p.ttsVoiceTone)
        assertEquals("my_duyen", TtsVoiceResolver.canonicalProfile("", p.ttsVoiceTone).id, "lần mở đầu (chưa lưu id) ra Mỹ Duyên")
    }

    @Test
    fun doiToiDoKhongDatLaiGiongVaCheDoOnline() = withRepo { repo ->
        repo.patchTtsPreferences(voiceProfileId = "minh_quang", voiceTone = "Minh Quang", onlineMode = false)
        repo.patchTtsPreferences(speed = 1.5f)
        val p = repo.profile.value
        assertEquals(1.5f, p.ttsSpeed)
        assertEquals("minh_quang", p.ttsVoiceProfileId)
        assertEquals("Minh Quang", p.ttsVoiceTone)
        assertEquals(false, p.ttsOnlineMode)
    }

    @Test
    fun goiThieuThamSoKhongDatLaiGiongVeMacDinh() = withRepo { repo ->
        repo.patchTtsPreferences(voiceProfileId = "dinh_trong", voiceTone = "Đình Trọng")
        // Lỗi cũ: tham số mặc định cứng "mai_phuong" / "Truyền cảm" / online = true
        repo.updateTtsPreferences(speed = 1.25f, autoScroll = false)
        val p = repo.profile.value
        assertEquals("dinh_trong", p.ttsVoiceProfileId)
        assertEquals("Đình Trọng", p.ttsVoiceTone)
        assertEquals(1.25f, p.ttsSpeed)
        assertEquals(false, p.ttsAutoScroll)
    }

    @Test
    fun idKhongTonTaiKhongBaoGioDuocLuuMaSuyRaTuAmSac() = withRepo { repo ->
        repo.patchTtsPreferences(voiceProfileId = "id_da_bi_bo", voiceTone = "Trầm ấm")
        assertEquals("minh_quang", repo.profile.value.ttsVoiceProfileId)
        assertEquals("Minh Quang", repo.profile.value.ttsVoiceTone)
    }

    @Test
    fun idHopLeThiTenHienThiLuonKhopId() = withRepo { repo ->
        repo.patchTtsPreferences(voiceProfileId = "thu_ha", voiceTone = "Mai Phương")
        assertEquals("thu_ha", repo.profile.value.ttsVoiceProfileId)
        assertEquals("Thu Hà", repo.profile.value.ttsVoiceTone)
    }

    @Test
    fun chiDoiAmSacThiIdDiTheoAmSac() = withRepo { repo ->
        // Lỗi cũ: id mặc định (đang lưu) thắng nên "Nam trầm" bị bỏ, vẫn giọng nữ
        repo.patchTtsPreferences(voiceTone = "Nam trầm (Hạ tông)")
        assertEquals("minh_quang", repo.profile.value.ttsVoiceProfileId)
        assertEquals("Nam", DEFAULT_VOICE_PROFILES.first { it.id == repo.profile.value.ttsVoiceProfileId }.gender)
    }
}
