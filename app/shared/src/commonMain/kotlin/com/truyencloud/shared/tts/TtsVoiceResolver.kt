package com.truyencloud.shared.tts

/**
 * Đổi mọi cách người dùng / bản cũ gọi tên một giọng ("âm sắc", tên hiển thị, id) về MỘT hồ sơ giọng hợp lệ.
 *
 * Mọi nơi chọn giọng (Cài đặt, Audiobook Studio, bộ đọc, hồ sơ đã lưu) phải đi qua đây: trước đây mỗi nơi tự `find` với
 * danh sách riêng nên "Nam trầm" ở giọng máy không khớp hồ sơ nào và âm thầm thành giọng NỮ mặc định.
 *
 * Hồ sơ hợp lệ là: giọng VieNeu (danh mục máy chủ đang dùng, hoặc bản đi kèm của cả hai mô hình) và mười hồ sơ cũ
 * ([DEFAULT_VOICE_PROFILES], id của ba âm sắc giọng máy).
 */
object TtsVoiceResolver {
    /** Ba âm sắc của giọng máy, đúng thứ tự hiển thị. Giá trị dài cũ ("Nữ chuẩn (Hệ thống)"...) vẫn được nhận. */
    val OFFLINE_TONES: List<String> = listOf("Nữ chuẩn", "Nam trầm", "Mặc định")

    private val defaultId: String get() = TtsVoiceCatalog.defaultProfile().id

    private fun legacy(id: String): TtsVoiceProfile? = DEFAULT_VOICE_PROFILES.firstOrNull { it.id == id }

    /** Hồ sơ có đúng id [id] (giọng VieNeu hoặc hồ sơ cũ), hoặc null. */
    fun byId(id: String): TtsVoiceProfile? = legacy(id) ?: TtsVoiceCatalog.knownVoice(id)

    fun resolveToneProfileId(tone: String): String {
        val key = tone.trim().lowercase()
        if (key.isEmpty()) return defaultId
        byId(key)?.let { return it.id }
        return when {
            key.startsWith("nữ chuẩn") -> "mai_phuong"
            key.startsWith("nam trầm") -> "minh_quang"
            key.startsWith("mặc định") || key == "google ai" -> "google_std"
            // Tên giọng của máy chủ đời trước từng có thể bị lưu làm id: chỉ còn là gợi ý giới tính
            "namminh" in key -> "minh_quang"
            "hoaimy" in key -> "mai_phuong"
            else -> LEGACY_TONES[key] ?: findByName(key)?.id ?: defaultId
        }
    }

    /** Tên / sắc thái của hồ sơ cũ trước (giữ hành vi cũ), rồi tên / phong cách của giọng VieNeu. */
    private fun findByName(key: String): TtsVoiceProfile? =
        DEFAULT_VOICE_PROFILES.find { it.name.contains(key, ignoreCase = true) || it.tag.contains(key, ignoreCase = true) }
            ?: TtsVoiceCatalog.voices.find { it.name.equals(key, ignoreCase = true) }
            ?: TtsVoiceCatalog.voices.find { it.name.contains(key, ignoreCase = true) }

    /** Hồ sơ ứng với [idOrTone]: id đúng thì lấy đúng nó, không thì coi là tên âm sắc; không nhận ra thì giọng mặc định. */
    fun profileFor(idOrTone: String): TtsVoiceProfile =
        byId(idOrTone) ?: resolveToneProfileId(idOrTone).let { id -> byId(id) ?: TtsVoiceCatalog.defaultProfile() }

    /**
     * Hồ sơ đúng cho cặp (id, âm sắc) đã lưu. Id hợp lệ thì tin id; id rỗng / không còn tồn tại (bản cũ chỉ lưu âm sắc)
     * thì suy từ âm sắc — trước đây bị thay bằng giọng nữ mặc định dù người dùng đã chọn giọng nam.
     */
    fun canonicalProfile(storedId: String, storedTone: String): TtsVoiceProfile =
        byId(storedId) ?: profileFor(storedTone)

    /** Âm sắc giọng máy tương ứng với [profile] (để đánh dấu đúng chip đang chọn). */
    fun offlineToneOf(profile: TtsVoiceProfile): String = when {
        profile.id == "google_std" -> OFFLINE_TONES[2]
        profile.gender.equals("Nam", ignoreCase = true) -> OFFLINE_TONES[1]
        else -> OFFLINE_TONES[0]
    }

    private val LEGACY_TONES = mapOf(
        "trầm ấm" to "minh_quang",
        "truyền cảm" to "mai_phuong",
        "dịu dàng" to "thao_vy",
        "phóng khoáng" to "bao_long",
        "huyền bí" to "thu_ha",
        "hào sảng" to "dinh_trong",
        "kiếm hiệp" to "dinh_trong",
        "trong trẻo" to "cam_tu",
        "ngôn tình" to "cam_tu",
        "thanh xuân" to "bao_anh",
        "nhẹ nhàng" to "bao_anh",
        "diễn đọc" to "hoang_nam",
        "năng động" to "hoang_nam"
    )
}
