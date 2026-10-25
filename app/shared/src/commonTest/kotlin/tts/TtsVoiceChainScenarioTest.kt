package com.truyencloud.shared.tts

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nửa phía app của hợp đồng "giọng nghe được == giọng đã chọn": từ lựa chọn của người dùng, qua bộ đọc, tới URL
 * `/v1/tts/stream` mà bộ phát thật gọi (cùng `TtsStreamUrl.build` với Android và iOS). Nửa phía máy chủ cùng bảng kịch bản
 * nằm ở server/tests/test_tts_voice_chain_contract.py (id + lang trong URL -> giọng neural thật). Đổi bảng ở một bên thì
 * đổi cả bên kia.
 */
class TtsVoiceChainScenarioTest {

    @AfterTest
    fun clearContext() = TtsTextLanguage.clearContext()

    private val vietnameseChapter = listOf(
        "Lâm Phong chậm rãi ngẩng đầu, ánh mắt lạnh lẽo nhìn về phía xa.",
        "“Ngươi là ai?” hắn hỏi.",
        "Minh.",
        "12",
        "***",
        "Hắn thi triển 九阳神功 để đối phó.",
        "Hello, how are you?"
    )
    private val bilingualChapter = listOf(
        "She walked into the room and looked around.",
        "Nguyễn Văn An was waiting there.",
        "Sorry.",
        "Xin chào, bạn khỏe không?"
    )
    private val chineseChapter = listOf("林风缓缓抬起头，目光冷冽地望向远方。", "第12章", "Lin Feng", "12")

    /** (đoạn, `voice=`, `lang=`) mà bộ phát thật gửi cho từng đoạn ĐỌC ĐƯỢC của chương; đoạn không đọc được bị bỏ qua. */
    private fun requests(speaker: FakeTtsSpeaker, chapter: List<String>): List<Triple<String, String, String>> =
        chapter.indices.filter { TtsSegmentPlan.isSpeakable(chapter[it]) }.map { i ->
            val text = chapter[i].trim()
            val url = TtsStreamUrl.build(
                "https://h", text, TtsSegmentPlan.prefetchHint(chapter, i),
                speaker.currentVoiceProfile.value.id
            )
            Triple(text, param(url, "voice"), param(url, "lang"))
        }

    private fun param(url: String, name: String): String =
        url.substringAfter("?").split("&").first { it.startsWith("$name=") }.substringAfter("=")

    private fun start(profile: String, chapter: List<String>, speed: Float = 1.0f): FakeTtsSpeaker =
        FakeTtsSpeaker().also {
            it.setVoiceProfile(profile)
            it.setSpeed(speed)
            it.speak(chapter, 0)
        }

    @Test
    fun truyenVietGiongNuMaiPhuongMoiDoanGuiDungIdVaLang() {
        val s = start("mai_phuong", vietnameseChapter)
        val got = requests(s, vietnameseChapter)
        assertTrue(got.all { it.second == "mai_phuong" }, "$got")
        assertEquals(
            listOf("vi", "vi", "vi", "vi", "vi", "en"),
            got.map { it.third },
            "Tên không dấu 'Minh.' và số '12' phải theo chương Việt; chỉ câu Anh rõ ràng mới là en: $got"
        )
        // "***" không phải là chữ: không bao giờ gửi lên máy chủ (HTTP 500 -> bộ ngắt mạch -> giọng máy)
        assertTrue(got.none { it.first == "***" })
    }

    @Test
    fun truyenVietGiongNamMinhQuangGuiIdNam() {
        val s = start("minh_quang", vietnameseChapter)
        assertTrue(requests(s, vietnameseChapter).all { it.second == "minh_quang" })
    }

    @Test
    fun songNguTiengAnhDocBangEnVaCauVietXenKeDocBangVi() {
        val s = start("minh_quang", bilingualChapter)
        val got = requests(s, bilingualChapter)
        assertEquals(listOf("en", "en", "en", "vi"), got.map { it.third }, "$got")
        assertTrue(got.all { it.second == "minh_quang" }, "Giọng nam giữ nguyên id ở mọi ngôn ngữ; máy chủ chọn Guy / Yunxi")
    }

    @Test
    fun truyenGocTiengTrungDocBangZhVaChuLatinLeVanLaAnh() {
        for (profile in listOf("mai_phuong", "minh_quang")) {
            val s = start(profile, chineseChapter)
            assertEquals(listOf("zh", "zh", "en", "zh"), requests(s, chineseChapter).map { it.third }, profile)
        }
    }

    @Test
    fun chipGiongMayNamTramRaIdNamKhongPhaiMaiPhuong() {
        val s = FakeTtsSpeaker()
        s.setVoiceTone("Nam trầm (Hạ tông)")
        s.speak(vietnameseChapter, 0)
        assertEquals("minh_quang", requests(s, vietnameseChapter).first().second)
    }

    @Test
    fun ngheThuMoiHoSoGuiDungIdVaLaTiengViet() {
        for (profile in DEFAULT_VOICE_PROFILES) {
            // Hai câu mẫu của màn Cài đặt (TtsVoiceChoices.previewSamples): tên + giới tính nghe được
            val gender = if (profile.gender == "Nam") "nam" else "nữ"
            val samples = listOf(
                if (profile.id == "google_std") "Xin chào! Đây là giọng mặc định." else "Xin chào! Tôi là ${profile.name}.",
                "Đây là giọng đọc $gender của TruyenCloud, sẵn sàng đọc truyện cho bạn."
            )
            for (sample in samples) {
                val url = TtsStreamUrl.build("https://h", sample, null, profile.id)
                assertEquals(profile.id, param(url, "voice"))
                assertEquals("vi", param(url, "lang"), "'$sample' phải đọc bằng giọng Việt")
            }
        }
    }

    @Test
    fun doiGiongGiuaChuongDocLaiDoanDangDocBangGiongMoiVaToc2DoKhongDoiGiong() {
        val s = start("mai_phuong", vietnameseChapter)
        s.finish(0)
        s.setVoiceProfile("minh_quang")
        s.setSpeed(1.5f)
        // Đoạn đang đọc được đọc lại với giọng mới (không phát tiếp âm thanh cũ), không nhảy đoạn
        assertEquals(1, s.currentParagraphIndex.value)
        assertEquals(listOf("play:0", "play:1", "play:1", "play:1"), s.plays())
        assertTrue(s.log.contains("voice:minh_quang"))
        val got = requests(s, vietnameseChapter)
        assertTrue(got.all { it.second == "minh_quang" }, "$got")
        assertEquals(1.5f, s.speed.value)
    }
}
