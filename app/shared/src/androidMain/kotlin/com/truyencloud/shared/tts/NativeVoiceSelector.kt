package com.truyencloud.shared.tts

import java.text.Normalizer

/** Ảnh chụp thuần của một giọng hệ thống (android.speech.tts.Voice) để xếp hạng mà không cần Android. */
internal data class NativeVoiceInfo(
    val name: String,
    /** Mã ngôn ngữ ISO-639-1 viết thường đã chuẩn hoá: "vi", "en", "zh"... */
    val language: String,
    /** Mã quốc gia viết hoa ("VN", "US", "CN"...) hoặc rỗng. */
    val country: String,
    val installed: Boolean,
    val networkRequired: Boolean,
    val quality: Int
)

internal enum class VoiceGender { FEMALE, MALE, UNKNOWN }

/**
 * Chọn giọng máy cho một hồ sơ giọng và một ngôn ngữ.
 *
 * Lỗi cũ (đều làm người nghe nghe SAI giọng): bước dự phòng bỏ qua giới tính (hồ sơ nam rơi vào giọng nữ dù máy có
 * giọng nam), giọng chưa tải vẫn được ưu tiên hơn giọng đã tải, đoạn Anh / Trung lấy giọng đầu tiên trong một Set không
 * có thứ tự (bất kỳ giới tính, bất kỳ vùng), thẻ "#male_1" bị coi là cả hai giới.
 */
internal object NativeVoiceSelector {

    /** Giọng Google TTS tiếng Việt theo thứ tự ưu tiên mặc định; "local" xếp cuối. */
    private val PREFERRED_TOKENS = listOf("vic", "vie", "vif", "vid", "gft", "local")
    private val FEMALE_TOKENS = setOf("vic", "vie", "gft", "via")
    private val MALE_TOKENS = setOf("vid", "vif", "vib")
    private val TOKEN_SPLIT = Regex("[^a-z0-9]+")
    private val SAMSUNG_CODE = Regex("^([fm])\\d{2}$")

    private val ISO3_TO_ISO1 = mapOf(
        "vie" to "vi", "eng" to "en", "zho" to "zh", "chi" to "zh", "cmn" to "zh"
    )

    /** "vie" / "vi_VN" / "VI" -> "vi"; mã lạ giữ nguyên (đã viết thường). */
    fun canonicalLanguage(raw: String): String {
        val code = raw.trim().lowercase().substringBefore('_').substringBefore('-')
        return ISO3_TO_ISO1[code] ?: code
    }

    /**
     * Giới tính suy từ TÊN giọng (Android không có API giới tính). Thẻ tường minh ("female", "male", "SMTf00") thắng
     * mã giọng Google, vì "vi-vn-x-vic#male_1-local" là giọng vic nhưng biến thể nam.
     */
    fun genderOf(name: String): VoiceGender {
        val lower = name.lowercase()
        if (lower.contains("female")) return VoiceGender.FEMALE
        if (lower.contains("male")) return VoiceGender.MALE
        if (lower.contains("smtf")) return VoiceGender.FEMALE
        if (lower.contains("smtm")) return VoiceGender.MALE
        val tokens = lower.split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        for (token in tokens) {
            SAMSUNG_CODE.matchEntire(token)?.let { return if (it.groupValues[1] == "f") VoiceGender.FEMALE else VoiceGender.MALE }
        }
        val female = tokens.any { it in FEMALE_TOKENS }
        val male = tokens.any { it in MALE_TOKENS }
        return when {
            female && !male -> VoiceGender.FEMALE
            male && !female -> VoiceGender.MALE
            else -> VoiceGender.UNKNOWN
        }
    }

    /**
     * Hệ số hạ cao độ cho hồ sơ NAM phải đọc bằng một giọng máy đã biết là NỮ (máy không có giọng nam tiếng Việt). Giống
     * iOS (IosVoiceSelection.pitch): hồ sơ nam vẫn nghe trầm hơn hồ sơ nữ thay vì y hệt. Hệ số nhân vào cao độ của hồ sơ để
     * Đình Trọng vẫn trầm hơn Hoàng Nam (0.92 -> ~0.70, 1.02 -> ~0.78).
     */
    private const val MALE_ON_FEMALE_VOICE_PITCH_FACTOR = 0.76f

    /**
     * Cao độ (setPitch) khi đọc [language] bằng giọng [voiceName]. Chỉ tiếng Việt mới dùng cao độ của hồ sơ (máy chủ đọc
     * Anh / Trung ở cao độ chuẩn nên giọng máy cũng vậy). Chỉ hạ khi CHẮC giọng đang dùng là nữ; giọng chưa rõ giới tính giữ
     * nguyên cao độ hồ sơ.
     */
    fun pitchFor(profile: TtsVoiceProfile?, language: String, voiceName: String?): Float {
        if (profile == null || canonicalLanguage(language) != "vi") return 1.0f
        val knownFemale = voiceName != null && genderOf(voiceName) == VoiceGender.FEMALE
        return if (wantedGender(profile) == VoiceGender.MALE && knownFemale) {
            profile.pitch * MALE_ON_FEMALE_VOICE_PITCH_FACTOR
        } else {
            profile.pitch
        }
    }

    /** Giới tính hồ sơ muốn; UNKNOWN ("Tự động" hoặc không có hồ sơ) = không ràng buộc. */
    fun wantedGender(profile: TtsVoiceProfile?): VoiceGender {
        // NFC: "Nữ" gõ dạng tổ hợp (u + dấu móc + dấu ngã) vẫn phải khớp, kẻo hồ sơ nữ bị coi là "không ràng buộc"
        val raw = Normalizer.normalize(profile?.gender.orEmpty().trim(), Normalizer.Form.NFC).lowercase()
        return when (raw) {
            "nam", "male" -> VoiceGender.MALE
            "nữ", "nu", "female" -> VoiceGender.FEMALE
            else -> VoiceGender.UNKNOWN
        }
    }

    /**
     * Thứ tự thử giọng của [language] cho [profile]: đã tải trước; rồi đúng giới tính > chưa rõ giới tính > sai giới
     * tính; rồi (tiếng Việt) đúng mã giọng của hồ sơ / (Anh, Trung) đúng vùng máy chủ dùng (en-US, zh-CN); giọng cục bộ
     * trước giọng cần mạng; rồi chất lượng. Danh sách rỗng = máy không có giọng nào của ngôn ngữ đó.
     */
    fun rank(voices: List<NativeVoiceInfo>, language: String, profile: TtsVoiceProfile?): List<NativeVoiceInfo> {
        val lang = canonicalLanguage(language)
        val want = wantedGender(profile)
        val prefix = profile?.targetVoicePrefix.orEmpty().lowercase()
        return voices
            .filter { it.language == lang }
            .sortedWith(
                compareBy<NativeVoiceInfo> { if (it.installed) 0 else 1 }
                    .thenBy { genderTier(genderOf(it.name), want) }
                    .thenBy { regionTier(it, lang, prefix) }
                    .thenBy { if (it.networkRequired) 1 else 0 }
                    .thenBy { preferredIndex(it.name) }
                    .thenByDescending { it.quality }
                    .thenBy { it.name }
            )
    }

    private fun genderTier(actual: VoiceGender, want: VoiceGender): Int = when {
        want == VoiceGender.UNKNOWN || actual == want -> 0
        actual == VoiceGender.UNKNOWN -> 1
        else -> 2
    }

    private fun regionTier(v: NativeVoiceInfo, lang: String, prefix: String): Int = when (lang) {
        "vi" -> if (matchesPrefix(v.name, prefix)) 0 else 1
        "en" -> if (v.country == "US") 0 else 1
        "zh" -> when (v.country) {
            "CN", "SG" -> 0
            "TW" -> 1
            "HK", "MO" -> 3
            else -> 2
        }
        else -> 0
    }

    /** Mã giọng ("vic", "vif"...) phải là MỘT thành phần của tên, không phải chuỗi con; tiền tố có dấu '-' ("vi-vn") thì so chuỗi. */
    private fun matchesPrefix(name: String, prefix: String): Boolean {
        if (prefix.isEmpty()) return false
        val lower = name.lowercase()
        if (prefix.any { !it.isLetterOrDigit() }) return lower.contains(prefix)
        return prefix in lower.split(TOKEN_SPLIT)
    }

    private fun preferredIndex(name: String): Int {
        val tokens = name.lowercase().split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        val idx = PREFERRED_TOKENS.indexOfFirst { it in tokens }
        return if (idx < 0) PREFERRED_TOKENS.size else idx
    }
}
