package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.tts.LegacyVoices
import com.truyencloud.shared.tts.TTS_VOICE_CATALOG_NOTE
import com.truyencloud.shared.tts.TtsVoiceCatalog
import com.truyencloud.shared.tts.TtsVoiceProfile
import com.truyencloud.shared.tts.TtsVoiceResolver

/**
 * Cách giao diện chọn và mô tả giọng đọc, dùng chung cho tờ cài đặt nghe đọc (màn đọc) và Cài đặt. Hàm thuần, không dính
 * Compose; mọi phép suy giọng đi qua [TtsVoiceResolver] / [TtsVoiceCatalog] để cùng một quy tắc với bộ đọc và máy chủ.
 *
 * Danh sách giọng AI là giọng THẬT của mô hình máy chủ đang chạy (tải lúc chạy, có bản đi kèm). Mười hồ sơ cũ không hiện
 * trong danh sách (chúng chỉ là giọng mặc định cùng giới tính), nhưng id đã lưu vẫn dùng được: hàng "Đang dùng" là giọng
 * máy chủ thật sự đọc cho id đó ([TtsVoiceCatalog.heardVoice]).
 */
internal object TtsVoiceChoices {
    private const val FEMALE = "Nữ"
    private const val MALE = "Nam"
    private const val ALL = "Tất cả"
    private const val REGION_PREFIX = "Miền "

    /** Hồ sơ mặc định: tên của nó ("Giọng mặc định") không đọc được trong câu "Tôi là ...". */
    private const val STANDARD_ID = "google_std"

    /** Chip lọc: giới tính, rồi các MIỀN có trong danh mục ("Miền Nam" để không lẫn với giới tính "Nam"). */
    fun categories(profiles: List<TtsVoiceProfile>): List<String> {
        val regions = listOf("Bắc", "Trung", "Nam").filter { r -> profiles.any { it.region == r } }
        return listOf(ALL, FEMALE, MALE) + if (regions.size > 1) regions.map { REGION_PREFIX + it } else emptyList()
    }

    val CATEGORIES: List<String> get() = categories(TtsVoiceCatalog.voices)

    /** Giải thích dưới danh sách giọng AI: điều người nghe cần biết để không kỳ vọng sai. */
    const val AI_VOICE_NOTE = TTS_VOICE_CATALOG_NOTE

    /** Giọng AI có thể chọn: danh mục của máy chủ, không có hồ sơ cũ. */
    fun aiVoices(profiles: List<TtsVoiceProfile>): List<TtsVoiceProfile> = profiles.filterNot { LegacyVoices.isLegacy(it.id) }

    /** Giọng đang dùng: id hợp lệ thì chính nó; id rỗng / không còn tồn tại thì suy từ [storedTone] (xem [TtsVoiceResolver]). */
    fun active(storedId: String, storedTone: String = ""): TtsVoiceProfile =
        TtsVoiceResolver.canonicalProfile(storedId, storedTone)

    /** Giọng máy chủ THẬT SỰ đọc cho id đã lưu (hồ sơ cũ -> giọng mặc định cùng giới tính): hàng được đánh dấu "Đang dùng". */
    fun heard(storedId: String, storedTone: String = ""): TtsVoiceProfile =
        TtsVoiceCatalog.heardVoice(active(storedId, storedTone).id)

    /** Giới tính NGHE ĐƯỢC của giọng AI (hồ sơ cũ "Tự động" ở chế độ AI là giọng nữ mặc định). */
    fun aiGender(profile: TtsVoiceProfile): String = if (TtsVoiceCatalog.heardVoice(profile.id).gender == MALE) MALE else FEMALE

    /** Dòng mô tả hàng giọng AI: giới tính, miền thật, phong cách. */
    fun subtitle(profile: TtsVoiceProfile): String = "${aiGender(profile)} · miền ${profile.region} · ${profile.tag}"

    fun filter(profiles: List<TtsVoiceProfile>, category: String): List<TtsVoiceProfile> {
        val voices = aiVoices(profiles)
        return when {
            category == FEMALE || category == MALE -> voices.filter { aiGender(it) == category }
            category.startsWith(REGION_PREFIX) -> voices.filter { it.region == category.removePrefix(REGION_PREFIX) }
            else -> voices
        }
    }

    /** Hai câu nghe thử giọng AI: nói tên giọng thật và miền giọng. */
    fun previewSamples(profile: TtsVoiceProfile): List<String> {
        val heard = TtsVoiceCatalog.heardVoice(profile.id)
        return listOf(
            if (profile.id == STANDARD_ID) "Xin chào! Đây là giọng mặc định." else "Xin chào! Tôi là ${heard.name}.",
            "Đây là giọng đọc miền ${heard.region} của TruyenCloud, sẵn sàng đọc truyện cho bạn."
        )
    }

    /** Chip âm sắc của giọng máy ([TtsVoiceResolver.OFFLINE_TONES]) có phải âm sắc của giọng đang dùng không. */
    fun isToneSelected(tone: String, active: TtsVoiceProfile): Boolean = TtsVoiceResolver.offlineToneOf(active) == tone

    /** Hồ sơ cần đặt khi chạm [tone]: đã đúng âm sắc thì giữ giọng hiện tại, không bắt người dùng mất giọng đã chọn. */
    fun profileIdForTone(tone: String, active: TtsVoiceProfile): String =
        if (isToneSelected(tone, active)) active.id else TtsVoiceResolver.resolveToneProfileId(tone)
}
