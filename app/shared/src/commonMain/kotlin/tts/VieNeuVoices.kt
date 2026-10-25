package com.truyencloud.shared.tts

/**
 * Bản sao ĐI KÈM app của danh mục giọng VieNeu-TTS trên máy chủ (server/services/tts_catalog_data.py, chép nguyên văn từ
 * `Vieneu(mode=...).list_preset_voices()`). Dùng khi chưa tải được danh mục thật ở `/v1/tts/voices` (lần mở đầu, mất mạng)
 * và để nhận ra id giọng của CẢ HAI mô hình: máy chủ đổi Nano <-> Turbo thì id người dùng đã chọn vẫn biết giới tính.
 * Cùng một tên ở hai mô hình là hai bản ghi khác nhau (vd. Mỹ Duyên: Nano ghi miền Bắc, Turbo ghi miền Nam).
 */
object VieNeuVoices {
    const val MODE_NANO = "v3nano"
    const val MODE_TURBO = "v3turbo"

    /** Mô hình máy chủ chạy mặc định (chủ app chọn Nano: nhanh hơn thời gian thực trên MiniPC). */
    const val DEFAULT_MODE = MODE_NANO

    private class Row(val id: String, val name: String, val gender: String, val region: String, val style: String)

    private val NANO = listOf(
        Row("adam", "Adam", "Nam", "Nam", "tự nhiên"),
        Row("ai_han", "Ái Hân", "Nữ", "Nam", "tin tức"),
        Row("my_duyen", "Mỹ Duyên", "Nữ", "Bắc", "đọc truyện"),
        Row("duc_tri", "Đức Trí", "Nam", "Bắc", "đọc truyện"),
        Row("huu_quan", "Hữu Quân", "Nam", "Bắc", "tin tức"),
        Row("xuan_tien", "Xuân Tiên", "Nữ", "Bắc", "tin tức"),
        Row("mai_anh", "Mai Anh", "Nữ", "Bắc", "tin tức"),
        Row("truc_ly", "Trúc Ly", "Nữ", "Bắc", "tự nhiên"),
        Row("anh_khoi", "Anh Khôi", "Nam", "Bắc", "kể chuyện"),
        Row("minh_quan", "Minh Quân", "Nam", "Bắc", "tự nhiên"),
        Row("manh_dung", "Mạnh Dũng", "Nam", "Bắc", "tự nhiên")
    )

    private val TURBO = listOf(
        Row("adam_bua", "Adam bựa", "Nam", "Bắc", "tự nhiên"),
        Row("truc_ly", "Trúc Ly", "Nữ", "Bắc", "tự nhiên"),
        Row("thien_minh", "Thiện Minh", "Nam", "Bắc", "kể chuyện"),
        Row("mai_anh", "Mai Anh", "Nữ", "Bắc", "tin tức"),
        Row("hai_dang", "Hải Đăng", "Nam", "Bắc", "tự nhiên"),
        Row("thuy_dung", "Thùy Dung", "Nữ", "Nam", "tin tức"),
        Row("thien_tam_duc", "Thiền Tâm Đức", "Nam", "Bắc", "kể chuyện"),
        Row("ngoc_huyen", "Ngọc Huyền", "Nữ", "Bắc", "tự nhiên"),
        Row("quang_son", "Quang Sơn", "Nam", "Trung", "tự nhiên"),
        Row("ngoc_tran", "Ngọc Trân", "Nữ", "Trung", "tự nhiên"),
        Row("minh_duc", "Minh Đức", "Nam", "Bắc", "tin tức"),
        Row("pham_tuyen", "Phạm Tuyên", "Nam", "Bắc", "tự nhiên"),
        Row("thai_son", "Thái Sơn", "Nam", "Nam", "kể chuyện"),
        Row("xuan_vinh", "Xuân Vĩnh", "Nam", "Bắc", "tự nhiên"),
        Row("thanh_binh", "Thanh Bình", "Nam", "Bắc", "kể chuyện"),
        Row("ngoc_linh", "Ngọc Linh", "Nữ", "Bắc", "kể chuyện"),
        Row("doan_trang", "Đoan Trang", "Nữ", "Bắc", "tự nhiên"),
        Row("thuc_doan", "Thục Đoan", "Nữ", "Nam", "kể chuyện"),
        Row("minh_triet", "Minh Triết", "Nam", "Nam", "tin tức"),
        Row("my_duyen", "Mỹ Duyên", "Nữ", "Nam", "đọc truyện"),
        Row("quynh_anh", "Quỳnh Anh", "Nữ", "Bắc", "đọc truyện"),
        Row("duc_tri", "Đức Trí", "Nam", "Nam", "đọc truyện"),
        Row("kim_thanh", "Kim Thanh", "Nữ", "Nam", "đọc truyện"),
        Row("adam", "Adam", "Nam", "Nam", "tự nhiên"),
        Row("quoc_tuan", "Quốc Tuấn", "Nam", "Bắc", "tự nhiên")
    )

    /** (id nữ mặc định, id nam mặc định) theo mô hình — giống máy chủ. */
    private val DEFAULTS = mapOf(MODE_NANO to ("my_duyen" to "duc_tri"), MODE_TURBO to ("mai_anh" to "thien_minh"))

    fun defaults(mode: String): Pair<String, String> = DEFAULTS[mode] ?: DEFAULTS.getValue(DEFAULT_MODE)

    fun voices(mode: String): List<TtsVoiceProfile> = (if (mode == MODE_TURBO) TURBO else NANO).map { it.toProfile() }

    /** Danh mục đi kèm app của [mode] (mặc định Nano), đúng dạng danh mục tải từ máy chủ. */
    fun bundledSnapshot(mode: String = DEFAULT_MODE): TtsCatalogSnapshot {
        val m = if (mode == MODE_TURBO) MODE_TURBO else MODE_NANO
        val (female, male) = defaults(m)
        return TtsCatalogSnapshot(
            model = m,
            voices = voices(m),
            femaleDefault = female,
            maleDefault = male,
            legacyMap = LegacyVoices.mapTo(female, male),
            fromServer = false
        )
    }

    /** Giọng có id [id] ở bất kỳ mô hình nào (Nano trước), hoặc null. */
    fun anyMode(id: String): TtsVoiceProfile? =
        (NANO.firstOrNull { it.id == id } ?: TURBO.firstOrNull { it.id == id })?.toProfile()

    private fun Row.toProfile() = vieNeuProfile(id, name, gender, region, style, description = null)
}

/** Hồ sơ cho một giọng VieNeu: [TtsVoiceProfile.region] là MIỀN thật, [TtsVoiceProfile.tag] là phong cách đọc. */
internal fun vieNeuProfile(
    id: String,
    name: String,
    gender: String,
    region: String,
    style: String,
    description: String?
): TtsVoiceProfile {
    val male = gender == "Nam"
    val tag = style.replaceFirstChar { it.uppercaseChar() }
    return TtsVoiceProfile(
        id = id,
        name = name,
        region = region,
        gender = if (male) "Nam" else "Nữ",
        tag = tag,
        description = description ?: "Giọng thật, miền $region · ${if (male) "nam" else "nữ"} · phong cách $style",
        // Chỉ là gợi ý chọn giọng MÁY cùng giới tính khi phải đọc bằng giọng của điện thoại
        targetVoicePrefix = if (male) "vif" else "vic",
        pitch = 1.0f
    )
}
