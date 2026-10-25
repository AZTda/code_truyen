package com.truyencloud.shared.tts

/**
 * Mười hồ sơ giọng CŨ. Id của chúng đã được lưu ở máy người dùng (và là id của ba âm sắc giọng máy) nên giữ nguyên mãi.
 * Giọng AI của hồ sơ cũ do máy chủ quyết định: đọc bằng giọng mặc định cùng giới tính của mô hình đang chạy (Nano: Mỹ Duyên /
 * Đức Trí). Tông (`pitch`) và tiền tố giọng (`targetVoicePrefix`) chỉ còn tác dụng với giọng của điện thoại.
 */
val DEFAULT_VOICE_PROFILES: List<TtsVoiceProfile> = listOf(
    legacy("mai_phuong", "Mai Phương", "Nữ", "Truyền Cảm", "tông tự nhiên, nhịp chậm vừa", "vic", 1.0f),
    legacy("minh_quang", "Minh Quang", "Nam", "Trầm Ấm", "tông hơi trầm, nhịp chậm vừa", "vif", 0.98f),
    legacy("thao_vy", "Thảo Vy", "Nữ", "Dịu Dàng", "tông nhỉnh hơn mặc định một chút", "vie", 1.02f),
    legacy("bao_long", "Bảo Long", "Nam", "Phóng Khoáng", "tông gần mặc định", "vid", 1.0f),
    legacy("thu_ha", "Thu Hà", "Nữ", "Huyền Bí", "tông trầm", "gft", 0.98f),
    legacy("dinh_trong", "Đình Trọng", "Nam", "Hào Sảng", "tông trầm nhất", "vif", 0.92f),
    legacy("cam_tu", "Cẩm Tú", "Nữ", "Trong Trẻo", "tông cao nhất", "vie", 1.08f),
    legacy("bao_anh", "Bảo Anh", "Nữ", "Thanh Xuân", "tông cao", "vic", 1.05f),
    legacy("hoang_nam", "Hoàng Nam", "Nam", "Diễn Đọc", "tông sáng", "vid", 1.02f),
    // "Tự động" chỉ có nghĩa với giọng máy: không ràng buộc giới tính của giọng điện thoại. Giọng AI của hồ sơ này là giọng nữ.
    TtsVoiceProfile(
        id = "google_std",
        name = "Giọng mặc định",
        region = LegacyVoices.GROUP,
        gender = "Tự động",
        tag = "Mặc Định",
        description = "Giọng cũ. Giọng AI: giọng nữ mặc định của máy chủ. Giọng máy: giọng tiếng Việt mặc định của điện thoại.",
        targetVoicePrefix = "vi-vn",
        pitch = 1.0f
    )
)

private fun legacy(
    id: String,
    name: String,
    gender: String,
    tag: String,
    deviceTone: String,
    prefix: String,
    pitch: Float
) = TtsVoiceProfile(
    id = id,
    name = name,
    region = LegacyVoices.GROUP,
    gender = gender,
    tag = tag,
    description = "Giọng cũ. Giọng AI: giọng ${gender.lowercase()} mặc định của máy chủ. Giọng máy: $deviceTone.",
    targetVoicePrefix = prefix,
    pitch = pitch
)

object LegacyVoices {
    /** Nhãn nhóm thật thà cho hồ sơ cũ (không phải giọng vùng miền). */
    const val GROUP = "Giọng cũ"

    /** Giới tính GIỌNG AI của hồ sơ cũ, đúng như máy chủ (`google_std` là nữ). */
    private val MALE = setOf("minh_quang", "dinh_trong", "bao_long", "hoang_nam")

    fun isLegacy(id: String): Boolean = DEFAULT_VOICE_PROFILES.any { it.id == id }

    fun isMale(id: String): Boolean = id in MALE

    /** Hồ sơ cũ -> id giọng thật, theo giới tính (máy chủ cũ / bản đi kèm không có `legacy_map`). */
    fun mapTo(femaleDefault: String, maleDefault: String): Map<String, String> =
        DEFAULT_VOICE_PROFILES.associate { it.id to if (isMale(it.id)) maleDefault else femaleDefault }
}
