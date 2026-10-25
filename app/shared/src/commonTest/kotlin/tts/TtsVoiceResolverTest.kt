package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mọi cách gọi tên một giọng phải về ĐÚNG hồ sơ. Lỗi cũ: màn hình chọn "Âm sắc giọng máy" lưu "Nam trầm (Hạ tông)" mà
 * không hồ sơ nào khớp nên rơi về "mai_phuong" (giọng NỮ) — người dùng chọn giọng nam mà nghe giọng nữ.
 */
class TtsVoiceResolverTest {

    private fun genderOfTone(tone: String) = TtsVoiceResolver.profileFor(tone).gender

    @Test
    fun amSacGiongMayNamPhaiRaGiongNam() {
        assertEquals("minh_quang", TtsVoiceResolver.resolveToneProfileId("Nam trầm (Hạ tông)"))
        assertEquals("minh_quang", TtsVoiceResolver.resolveToneProfileId("Nam trầm"))
        assertEquals("Nam", genderOfTone("Nam trầm (Hạ tông)"))
        assertEquals("Nam", genderOfTone("  nam TRẦM "))
    }

    @Test
    fun amSacGiongMayNuVaMacDinh() {
        assertEquals("mai_phuong", TtsVoiceResolver.resolveToneProfileId("Nữ chuẩn (Hệ thống)"))
        assertEquals("mai_phuong", TtsVoiceResolver.resolveToneProfileId("Nữ chuẩn"))
        assertEquals("google_std", TtsVoiceResolver.resolveToneProfileId("Mặc định máy"))
        assertEquals("google_std", TtsVoiceResolver.resolveToneProfileId("Mặc định"))
    }

    @Test
    fun moiAmSacHienThiDeuRaDungAmSacDoKhiDoiNguocLai() {
        for (tone in TtsVoiceResolver.OFFLINE_TONES) {
            val profile = TtsVoiceResolver.profileFor(tone)
            assertEquals(tone, TtsVoiceResolver.offlineToneOf(profile), "Chip '$tone' phải được đánh dấu khi chọn xong")
        }
    }

    @Test
    fun amSacGiongMayCuaMoiHoSoLaMotTrongBaChip() {
        for (p in DEFAULT_VOICE_PROFILES) {
            assertTrue(TtsVoiceResolver.offlineToneOf(p) in TtsVoiceResolver.OFFLINE_TONES)
        }
        assertEquals("Nam trầm", TtsVoiceResolver.offlineToneOf(DEFAULT_VOICE_PROFILES.first { it.id == "hoang_nam" }))
        assertEquals("Nữ chuẩn", TtsVoiceResolver.offlineToneOf(DEFAULT_VOICE_PROFILES.first { it.id == "cam_tu" }))
    }

    @Test
    fun amSacCuCuaBanDauVanDuocNhan() {
        assertEquals("minh_quang", TtsVoiceResolver.resolveToneProfileId("Trầm ấm"))
        assertEquals("mai_phuong", TtsVoiceResolver.resolveToneProfileId("Truyền cảm"))
        assertEquals("thao_vy", TtsVoiceResolver.resolveToneProfileId("Dịu dàng"))
        assertEquals("bao_long", TtsVoiceResolver.resolveToneProfileId("Phóng khoáng"))
        assertEquals("thu_ha", TtsVoiceResolver.resolveToneProfileId("Huyền bí"))
        assertEquals("thu_ha", TtsVoiceResolver.resolveToneProfileId("Huyền Bí"))
        assertEquals("dinh_trong", TtsVoiceResolver.resolveToneProfileId("Hào sảng"))
        assertEquals("dinh_trong", TtsVoiceResolver.resolveToneProfileId("Kiếm hiệp"))
        assertEquals("cam_tu", TtsVoiceResolver.resolveToneProfileId("Trong trẻo"))
        assertEquals("bao_anh", TtsVoiceResolver.resolveToneProfileId("Thanh xuân"))
        assertEquals("hoang_nam", TtsVoiceResolver.resolveToneProfileId("Diễn đọc"))
    }

    @Test
    fun tenHienThiVaIdDeuRaDungHoSo() {
        for (p in DEFAULT_VOICE_PROFILES) {
            assertEquals(p.id, TtsVoiceResolver.resolveToneProfileId(p.id), "id ${p.id}")
            assertEquals(p.id, TtsVoiceResolver.resolveToneProfileId(p.name), "tên ${p.name}")
            assertEquals(p, TtsVoiceResolver.profileFor(p.id))
        }
    }

    @Test
    fun tenCuCuaGiongMacDinhVaTenGiongThanKinhCuaMayChu() {
        assertEquals("google_std", TtsVoiceResolver.resolveToneProfileId("Google AI"))
        assertEquals("minh_quang", TtsVoiceResolver.resolveToneProfileId("vi-VN-NamMinhNeural"))
        assertEquals("mai_phuong", TtsVoiceResolver.resolveToneProfileId("vi-VN-HoaiMyNeural"))
    }

    @Test
    fun khongNhanRaThiLaGiongMacDinh() {
        // Cài đặt mới / id lạ: giọng nữ mặc định của danh mục VieNeu (Nano: Mỹ Duyên)
        assertEquals("my_duyen", TtsVoiceResolver.resolveToneProfileId(""))
        assertEquals("my_duyen", TtsVoiceResolver.resolveToneProfileId("không có giọng này"))
        assertEquals("my_duyen", TtsVoiceResolver.profileFor("khong_co").id)
    }

    @Test
    fun hoSoDaLuuIdHopLeThiTinId() {
        assertEquals("thu_ha", TtsVoiceResolver.canonicalProfile("thu_ha", "Mai Phương").id)
        assertEquals("minh_quang", TtsVoiceResolver.canonicalProfile("minh_quang", "Truyền cảm").id)
    }

    @Test
    fun banCuChiLuuAmSacThiSuyRaGiongDaChon() {
        // Lỗi cũ: id rỗng -> mặc định "mai_phuong" bất kể người dùng đã chọn "Trầm ấm" (giọng nam)
        assertEquals("minh_quang", TtsVoiceResolver.canonicalProfile("", "Trầm ấm").id)
        assertEquals("minh_quang", TtsVoiceResolver.canonicalProfile("id_da_bi_bo", "Nam trầm (Hạ tông)").id)
        assertEquals("my_duyen", TtsVoiceResolver.canonicalProfile("", "").id)
    }
}
