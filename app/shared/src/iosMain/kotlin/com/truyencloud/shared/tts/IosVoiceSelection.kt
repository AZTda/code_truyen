package com.truyencloud.shared.tts

/** Giới tính giọng máy, đổi từ AVSpeechSynthesisVoiceGender. */
internal enum class IosVoiceGender { MALE, FEMALE, UNSPECIFIED }

/** Ngôn ngữ cần giọng máy; suy ra từ CHÍNH hàm nhận diện đã quyết định tham số `lang` gửi cho máy chủ. */
internal enum class IosSpeechLanguage(val code: String, val preferredLocale: String) {
    VIETNAMESE("vi", "vi-VN"),
    ENGLISH("en", "en-US"),
    CHINESE("zh", "zh-CN")
}

/** Những gì cần biết về một AVSpeechSynthesisVoice để chọn giọng; không phụ thuộc API Apple nên kiểm thử được. */
internal data class IosVoiceInfo(
    val identifier: String,
    val language: String,
    val quality: Long,
    val gender: IosVoiceGender
)

internal object IosVoiceMessages {
    const val NO_VIETNAMESE_VOICE =
        "Máy chưa cài giọng tiếng Việt nên không đọc được bằng giọng máy. Vào Cài đặt iOS > Trợ năng > Nội dung được " +
            "đọc > Giọng nói > Tiếng Việt để tải giọng Linh, hoặc bật giọng AI online."

    const val ONLINE_FAILED_NO_VIETNAMESE_VOICE =
        "Không kết nối được giọng AI online và máy cũng chưa cài giọng tiếng Việt. Hãy kiểm tra mạng hoặc địa chỉ máy " +
            "chủ, hoặc tải giọng Linh ở Cài đặt iOS > Trợ năng > Nội dung được đọc > Giọng nói > Tiếng Việt."

    /** Đang ở chế độ online thì lý do thật là máy chủ không phát được, không phải người dùng chọn giọng máy. */
    fun noVietnameseVoice(onlineMode: Boolean): String =
        if (onlineMode) ONLINE_FAILED_NO_VIETNAMESE_VOICE else NO_VIETNAMESE_VOICE
}

/**
 * Chọn giọng máy (AVSpeechSynthesizer) cho một đoạn. Trước đây:
 * - giới tính so bằng `gender.toString().contains("male")`, mà "...GenderFemale" cũng chứa "male" nên giọng nữ luôn
 *   bị coi là nam (hồ sơ nam đọc tiếng Anh / Trung ra giọng nữ, và không được hạ cao độ);
 * - lấy `firstOrNull()` của danh sách (thứ tự không được đảm bảo): có thể ra giọng zh-TW / Quảng Đông cho truyện Hán
 *   giản thể, giọng "Eloquence" / giọng vui nhộn (Albert, Bells...), hoặc gói Compact dù người dùng đã tải gói Nâng cao;
 * - không có giọng tiếng Việt thì lặng lẽ đọc bằng giọng en-US.
 */
internal object IosVoiceSelection {

    fun languageOf(text: String): IosSpeechLanguage = when (TtsTextLanguage.langParam(text)) {
        "zh" -> IosSpeechLanguage.CHINESE
        "en" -> IosSpeechLanguage.ENGLISH
        else -> IosSpeechLanguage.VIETNAMESE
    }

    /** Cùng cách máy chủ phân giọng nam / nữ: chỉ profile "Nam" là nam, "Nữ" và "Tự động" là nữ. */
    fun wantsMale(profile: TtsVoiceProfile): Boolean = profile.gender.equals("Nam", ignoreCase = true)

    fun matchesLanguage(tag: String, language: IosSpeechLanguage): Boolean {
        val t = tag.replace('_', '-')
        return t.equals(language.code, ignoreCase = true) || t.startsWith(language.code + "-", ignoreCase = true)
    }

    /**
     * Giọng tốt nhất cho [language]: đúng giới tính -> đúng vùng (vi-VN, en-US, zh-CN giản thể) -> chất lượng cao hơn
     * (Premium > Enhanced > Compact). Giọng vui nhộn / Eloquence chỉ dùng khi máy không có giọng nào khác. Kết quả
     * không phụ thuộc thứ tự danh sách đầu vào. Null nếu máy không có giọng nào của ngôn ngữ đó.
     */
    fun pick(voices: List<IosVoiceInfo>, language: IosSpeechLanguage, wantMale: Boolean): IosVoiceInfo? {
        val inLanguage = voices.filter { matchesLanguage(it.language, language) }
        val pool = inLanguage.filterNot { isNoveltyOrLegacy(it.identifier) }.ifEmpty { inLanguage }
        return pool.minWithOrNull(
            compareBy<IosVoiceInfo> { genderRank(it.gender, wantMale) }
                .thenBy { localeRank(it.language, language) }
                .thenByDescending { tier(it) }
                .thenBy { it.identifier }
        )
    }

    private fun genderRank(gender: IosVoiceGender, wantMale: Boolean): Int = when (gender) {
        IosVoiceGender.UNSPECIFIED -> 1
        IosVoiceGender.MALE -> if (wantMale) 0 else 2
        IosVoiceGender.FEMALE -> if (wantMale) 2 else 0
    }

    private fun localeRank(tag: String, language: IosSpeechLanguage): Int {
        val t = tag.replace('_', '-').lowercase()
        return when (language) {
            IosSpeechLanguage.VIETNAMESE -> if (t == "vi-vn") 0 else 1
            IosSpeechLanguage.ENGLISH -> if (t == "en-us") 0 else 1
            IosSpeechLanguage.CHINESE -> when {
                t == "zh-cn" || t.startsWith("zh-hans") -> 0
                t == "zh-hk" || t.startsWith("zh-hant-hk") -> 2 // Quảng Đông, không phải tiếng phổ thông
                else -> 1
            }
        }
    }

    private fun isNoveltyOrLegacy(identifier: String): Boolean =
        identifier.startsWith("com.apple.speech.synthesis.voice.") || identifier.startsWith("com.apple.eloquence.")

    private fun tier(v: IosVoiceInfo): Int {
        val id = v.identifier.lowercase()
        if ("super-compact" in id) return 0
        val fromId = when {
            "premium" in id -> 3
            "enhanced" in id -> 2
            else -> 1
        }
        return maxOf(v.quality.coerceIn(1L, 3L).toInt(), fromId)
    }

    /**
     * Cao độ giọng máy. Giọng tiếng Việt của iOS chỉ có "Linh" (nữ) nên profile nam phải hạ cao độ để khác profile nữ;
     * giọng đã là nam thật thì giữ cao độ profile. Tiếng Anh không đổi cao độ.
     */
    fun pitch(profile: TtsVoiceProfile, language: IosSpeechLanguage, voiceGender: IosVoiceGender?): Float = when {
        language == IosSpeechLanguage.ENGLISH -> 1.0f
        wantsMale(profile) -> if (voiceGender == IosVoiceGender.MALE) profile.pitch else loweredPitch(profile.id)
        else -> when (profile.id) {
            "cam_tu" -> 1.08f
            "bao_anh" -> 1.04f
            "thao_vy" -> 1.02f
            "thu_ha" -> 0.96f
            else -> 1.0f
        }
    }

    private fun loweredPitch(profileId: String): Float = when (profileId) {
        "dinh_trong" -> 0.68f
        "minh_quang" -> 0.70f
        "bao_long" -> 0.73f
        "hoang_nam" -> 0.76f
        else -> 0.72f
    }

    /**
     * (tốc độ thật nghe được, `AVSpeechUtterance.rate`). Thang rate của Apple KHÔNG tuyến tính: đo bằng
     * AVSpeechSynthesizer.writeUtterance (macOS 26, giọng Linh vi-VN và Samantha en-US cho cùng đường cong) thì
     * rate 0.5 = 1.0x, 0.625 = 1.7x, 0.75 = 2.5x, 1.0 = 4.0x; nên công thức cũ `0.5 * tốc độ` làm "1.5x" thành
     * ~2.5x và "2.0x" thành ~4x. Mỗi bậc ở đây là giữa khoảng rate cho ra cùng một tốc độ. Chưa đo trên máy iOS thật.
     */
    private val RATE_LEVELS: List<Pair<Float, Float>> = listOf(
        0.50f to 0.0125f, 0.52f to 0.025f, 0.55f to 0.0625f, 0.58f to 0.10f, 0.62f to 0.125f,
        0.66f to 0.1625f, 0.71f to 0.2125f, 0.76f to 0.275f, 0.83f to 0.3375f, 0.90f to 0.4125f,
        1.00f to 0.50f, 1.12f to 0.525f, 1.29f to 0.55f, 1.42f to 0.575f, 1.59f to 0.60f,
        1.71f to 0.625f, 1.82f to 0.65f, 1.95f to 0.675f, 2.10f to 0.70f
    )

    /** `rate` của AVSpeechUtterance cho tốc độ [speed] (0.5x-2.0x) của ứng dụng. */
    fun utteranceRate(speed: Float): Float {
        val s = speed.coerceIn(0.5f, 2.0f)
        return RATE_LEVELS.minByOrNull { kotlin.math.abs(it.first - s) }?.second ?: 0.5f
    }
}
