package com.truyencloud.shared.tts

import com.truyencloud.shared.library.FakeHomePrefs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Danh mục giọng tải từ `/v1/tts/voices` lúc chạy, lưu theo từng máy chủ, có bản Nano đi kèm khi chưa tải được. */
class TtsVoiceCatalogRepositoryTest {

    @AfterTest
    fun reset() = TtsVoiceCatalog.resetToBundled()

    /** Rút gọn từ phản hồi thật (routes/tts.py get_available_voices): hai giọng của mô hình Turbo. */
    private val turboBody = """
        {"status":"ok","engine":{"name":"vieneu","model":"v3turbo","state":"ready","model_loaded":true,
          "languages":["vi","en"],"device_languages":["zh"],"speed_control":"client"},
         "voices":[{"id":"mai_phuong","name":"Mai Phương"}],
         "catalog":[
           {"id":"mai_anh","name":"Mai Anh","gender":"Nữ","region":"Bắc","style":"tin tức","preset_name":"Mai Anh",
            "description":"Giọng thật, miền Bắc · nữ · phong cách tin tức","default_for":"female","model":"v3turbo","legacy_ids":["mai_phuong"]},
           {"id":"quang_son","name":"Quang Sơn","gender":"Nam","region":"Trung","style":"tự nhiên","preset_name":"Quang Sơn",
            "description":"Giọng thật, miền Trung · nam · phong cách tự nhiên","default_for":null,"model":"v3turbo","legacy_ids":[]}
         ],
         "default_voice":"mai_anh","default_voices":{"female":"mai_anh","male":"quang_son"},
         "legacy_map":{"mai_phuong":"mai_anh","minh_quang":"quang_son"},"note":"..."}
    """.trimIndent()

    @Test
    fun docDanhMucThatCuaMayChu() {
        val snap = TtsVoiceCatalogRepository.parse(turboBody)!!
        assertEquals("v3turbo", snap.model)
        assertEquals(listOf("mai_anh", "quang_son"), snap.voices.map { it.id })
        assertEquals("Trung", snap.voices[1].region, "miền THẬT của giọng")
        assertEquals("Tự nhiên", snap.voices[1].tag)
        assertEquals("quang_son", snap.maleDefault)
        assertEquals("quang_son", snap.legacyMap["minh_quang"])
        assertTrue(snap.fromServer)
    }

    @Test
    fun mayChuDoiTruocKhongCoDanhMucThiGiuBanDangCo() {
        assertNull(TtsVoiceCatalogRepository.parse("""{"status":"ok","voices":[{"id":"mai_phuong"}]}"""))
        assertNull(TtsVoiceCatalogRepository.parse("không phải json"))
        assertNull(TtsVoiceCatalogRepository.parse("""{"catalog":[]}"""))
    }

    @Test
    fun banLuuTheoTungMayChuChuaCoThiDungBanNanoDiKem() {
        val prefs = FakeHomePrefs()
        prefs.putString(TtsVoiceCatalogRepository.cacheKey("https://minipc.example/"), turboBody)
        val repo = TtsVoiceCatalogRepository(prefs)

        assertTrue(repo.applyCached("https://minipc.example"))
        assertEquals(listOf("mai_anh", "quang_son"), TtsVoiceCatalog.voices.map { it.id })
        assertEquals("quang_son", TtsVoiceCatalog.heardVoice("dinh_trong").id, "hồ sơ cũ nam -> giọng nam mặc định của máy chủ")
        assertEquals("mai_anh", TtsVoiceCatalog.defaultProfile().id)

        assertFalse(repo.applyCached("https://may-khac.example"))
        assertEquals(11, TtsVoiceCatalog.voices.size, "máy chủ khác chưa có bản lưu: bản Nano đi kèm")
        assertEquals("my_duyen", TtsVoiceCatalog.defaultProfile().id)
    }

    @Test
    fun idDaLuuKhongBaoGioBiDoiChiDoiGiongNgheDuoc() {
        TtsVoiceCatalog.update(TtsVoiceCatalogRepository.parse(turboBody)!!)
        // Người dùng đã chọn "my_duyen" (Nano) rồi máy chủ đổi sang danh mục không có giọng đó: id vẫn nhận ra, nghe giọng nữ mặc định
        assertEquals("my_duyen", TtsVoiceResolver.profileFor("my_duyen").id)
        assertEquals("mai_anh", TtsVoiceCatalog.heardVoice("my_duyen").id)
        assertEquals("quang_son", TtsVoiceCatalog.heardVoice("duc_tri").id)
    }
}
