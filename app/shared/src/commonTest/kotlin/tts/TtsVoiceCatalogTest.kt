package com.truyencloud.shared.tts

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Danh mục giọng phải KHỚP máy chủ (server/services/tts_local_catalog.py, chép từ `Vieneu(mode=...).list_preset_voices()`)
 * và nói thật: giọng VieNeu ghi miền thật; mười hồ sơ cũ là "Giọng cũ", đọc bằng giọng mặc định cùng giới tính.
 */
class TtsVoiceCatalogTest {

    @AfterTest
    fun reset() = TtsVoiceCatalog.resetToBundled()

    /** Đúng thứ tự / dữ liệu của danh mục Nano máy chủ đang chạy (scratchpad/vieneu_catalog.json). */
    private val serverNano = listOf(
        "adam|Adam|Nam|Nam|Tự nhiên", "ai_han|Ái Hân|Nữ|Nam|Tin tức", "my_duyen|Mỹ Duyên|Nữ|Bắc|Đọc truyện",
        "duc_tri|Đức Trí|Nam|Bắc|Đọc truyện", "huu_quan|Hữu Quân|Nam|Bắc|Tin tức", "xuan_tien|Xuân Tiên|Nữ|Bắc|Tin tức",
        "mai_anh|Mai Anh|Nữ|Bắc|Tin tức", "truc_ly|Trúc Ly|Nữ|Bắc|Tự nhiên", "anh_khoi|Anh Khôi|Nam|Bắc|Kể chuyện",
        "minh_quan|Minh Quân|Nam|Bắc|Tự nhiên", "manh_dung|Mạnh Dũng|Nam|Bắc|Tự nhiên"
    )

    private val legacyGender = mapOf(
        "mai_phuong" to "Nữ", "thao_vy" to "Nữ", "thu_ha" to "Nữ", "cam_tu" to "Nữ", "bao_anh" to "Nữ",
        "minh_quang" to "Nam", "dinh_trong" to "Nam", "bao_long" to "Nam", "hoang_nam" to "Nam", "google_std" to "Nữ"
    )

    @Test
    fun banDiKemLaDungMuoiMotGiongNanoCuaMayChu() {
        val bundled = VieNeuVoices.voices(VieNeuVoices.MODE_NANO).map { "${it.id}|${it.name}|${it.gender}|${it.region}|${it.tag}" }
        assertEquals(serverNano, bundled)
        assertEquals(25, VieNeuVoices.voices(VieNeuVoices.MODE_TURBO).size)
        assertEquals("my_duyen", TtsVoiceCatalog.defaultProfile().id, "cài đặt mới: Mỹ Duyên")
        assertEquals("duc_tri", TtsVoiceCatalog.defaultFor(male = true).id)
    }

    @Test
    fun idHoSoCuKhongDoiVaKhongTrung() {
        val ids = DEFAULT_VOICE_PROFILES.map { it.id }
        assertEquals(legacyGender.keys, ids.toSet(), "Id được lưu và gửi lên máy chủ: không được đổi / thiếu")
        assertEquals(ids.size, ids.toSet().size)
        for (p in DEFAULT_VOICE_PROFILES) assertTrue(TtsVoiceResolver.byId(p.id) != null, p.id)
    }

    @Test
    fun hoSoCuNoiThatLaGiongCuVaNgheBangGiongMacDinhCungGioiTinh() {
        for (p in DEFAULT_VOICE_PROFILES) {
            assertEquals(LegacyVoices.GROUP, p.region, p.id)
            assertTrue(p.description.startsWith("Giọng cũ"), p.id)
            val heard = TtsVoiceCatalog.heardVoice(p.id)
            assertEquals(if (legacyGender.getValue(p.id) == "Nam") "duc_tri" else "my_duyen", heard.id, p.id)
        }
    }

    @Test
    fun khongConNhacToiMicrosoft() {
        val banned = listOf("Microsoft", "Edge", "Azure", "Hoài My", "Nam Minh", "edge-tts")
        val texts = DEFAULT_VOICE_PROFILES.flatMap { listOf(it.name, it.tag, it.description) } +
            VieNeuVoices.voices(VieNeuVoices.MODE_NANO).flatMap { listOf(it.name, it.tag, it.description) } + TTS_VOICE_CATALOG_NOTE
        for (t in texts) for (word in banned) assertFalse(word in t, "'$t' chứa '$word'")
        assertTrue("MiniPC" in TTS_VOICE_CATALOG_NOTE && "liền mạch" in TTS_VOICE_CATALOG_NOTE)
    }

    @Test
    fun idCuaMoHinhKiaGiuGioiTinhIdLaThiGiongNu() {
        assertEquals("duc_tri", TtsVoiceCatalog.heardVoice("thien_minh").id, "giọng nam của Turbo khi máy chủ chạy Nano")
        assertEquals("my_duyen", TtsVoiceCatalog.heardVoice("ngoc_huyen").id)
        assertEquals("my_duyen", TtsVoiceCatalog.heardVoice("khong_co_giong_nay").id)
        assertEquals("anh_khoi", TtsVoiceCatalog.heardVoice("anh_khoi").id)
        assertTrue(TtsVoiceResolver.byId("minh_quan") != TtsVoiceResolver.byId("minh_quang"), "minh_quan (Nano) khác minh_quang (cũ)")
    }

    @Test
    fun tienToGiongMayPhuHopGioiTinh() {
        // Mã giọng Google tiếng Việt: vic / vie / gft là nữ, vid / vif là nam (bộ chọn giọng máy dựa vào đây)
        for (p in DEFAULT_VOICE_PROFILES + VieNeuVoices.voices(VieNeuVoices.MODE_NANO)) {
            when (p.gender) {
                "Nam" -> assertTrue(p.targetVoicePrefix in setOf("vid", "vif"), p.id)
                "Nữ" -> assertTrue(p.targetVoicePrefix in setOf("vic", "vie", "gft"), p.id)
            }
        }
    }

    @Test
    fun mayChuDoiSangTurboThiDanhMucVaMacDinhDoiTheo() {
        TtsVoiceCatalog.update(VieNeuVoices.bundledSnapshot(VieNeuVoices.MODE_TURBO))
        assertEquals(25, TtsVoiceCatalog.voices.size)
        assertEquals("mai_anh", TtsVoiceCatalog.defaultProfile().id)
        assertEquals("thien_minh", TtsVoiceCatalog.heardVoice("minh_quang").id)
        // Cùng tên ở Turbo là bản ghi riêng của Turbo (Đức Trí của Turbo ghi miền Nam)
        assertEquals("Nam", TtsVoiceCatalog.heardVoice("duc_tri").region)
        // Id chỉ có ở Nano vẫn nhận ra và giữ giới tính
        assertEquals("thien_minh", TtsVoiceCatalog.heardVoice("anh_khoi").id)
        assertEquals("mai_anh", TtsVoiceCatalog.heardVoice("ai_han").id)
    }

    @Test
    fun danhMucRongHoacHongBiBoQua() {
        TtsVoiceCatalog.update(VieNeuVoices.bundledSnapshot().copy(voices = emptyList()))
        assertEquals(11, TtsVoiceCatalog.voices.size)
        val broken = VieNeuVoices.bundledSnapshot().copy(femaleDefault = "khong_co", maleDefault = "")
        TtsVoiceCatalog.update(broken)
        assertEquals("adam", TtsVoiceCatalog.defaultFor(male = true).id, "giọng nam đầu tiên khi mặc định không có trong danh mục")
        assertEquals("ai_han", TtsVoiceCatalog.defaultProfile().id)
    }
}
