@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.truyencloud.shared.tts

import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesisVoiceGender
import platform.AVFAudio.AVSpeechUtterance

/**
 * Cầu nối giữa logic chọn giọng thuần ([IosVoiceSelection]) và AVSpeechSynthesizer. Đọc truyện và nghe thử cùng dựng
 * câu đọc qua [buildUtterance] nên chắc chắn nghe cùng một giọng, cao độ và tốc độ.
 */
internal object IosTtsVoiceMatcher {

    fun isEnglishText(text: String): Boolean = TtsTextLanguage.isEnglish(text)

    /** Không so chuỗi `toString()`: "AVSpeechSynthesisVoiceGenderFemale" cũng chứa "male". */
    fun genderOf(gender: AVSpeechSynthesisVoiceGender): IosVoiceGender = when (gender) {
        AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderMale -> IosVoiceGender.MALE
        AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderFemale -> IosVoiceGender.FEMALE
        else -> IosVoiceGender.UNSPECIFIED
    }

    private fun AVSpeechSynthesisVoice.toInfo() = IosVoiceInfo(
        identifier = identifier,
        language = language,
        quality = quality,
        gender = genderOf(gender)
    )

    /** Giọng cài trên máy cho [language], hoặc null nếu máy không có giọng nào của ngôn ngữ đó. */
    fun bestVoice(language: IosSpeechLanguage, wantMale: Boolean): AVSpeechSynthesisVoice? {
        val installed = AVSpeechSynthesisVoice.speechVoices().filterIsInstance<AVSpeechSynthesisVoice>()
        val best = IosVoiceSelection.pick(installed.map { it.toInfo() }, language, wantMale)
        if (best != null) return installed.firstOrNull { it.identifier == best.identifier }
        // Danh sách không có giọng nào: để hệ thống tự chọn, nhưng chỉ nhận nếu đúng ngôn ngữ (không nhận giọng ngoại)
        return listOf(language.preferredLocale, language.code).firstNotNullOfOrNull { tag ->
            AVSpeechSynthesisVoice.voiceWithLanguage(tag)?.takeIf { IosVoiceSelection.matchesLanguage(it.language, language) }
        }
    }

    fun hasVietnameseVoice(): Boolean = bestVoice(IosSpeechLanguage.VIETNAMESE, wantMale = false) != null

    /**
     * Dựng câu đọc bằng giọng máy cho [text]. Null nghĩa là máy không có giọng tiếng Việt cho đoạn tiếng Việt: đọc
     * bằng giọng ngoại ngữ nghe như đọc sai cả câu, nên người gọi phải báo lỗi thay vì phát.
     * Máy thiếu giọng Anh / Trung thì để giọng mặc định của hệ thống.
     */
    fun buildUtterance(
        text: String,
        profile: TtsVoiceProfile,
        speed: Float,
        volume: Float = 1.0f,
        languageOf: String = text
    ): AVSpeechUtterance? {
        // Đọc tiếp giữa mảnh: ngôn ngữ theo CẢ mảnh ([languageOf]), phần đuôi ngắn có thể bị đoán sai ngôn ngữ
        val language = IosVoiceSelection.languageOf(languageOf)
        val voice = bestVoice(language, IosVoiceSelection.wantsMale(profile))
        if (voice == null && language == IosSpeechLanguage.VIETNAMESE) return null
        val utterance = AVSpeechUtterance(string = text)
        utterance.voice = voice
        utterance.pitchMultiplier = IosVoiceSelection.pitch(profile, language, voice?.let { genderOf(it.gender) })
        utterance.rate = IosVoiceSelection.utteranceRate(speed)
        utterance.volume = volume.coerceIn(0.05f, 1.0f)
        return utterance
    }

    /** Tên giọng tiếng Việt thật sự sẽ đọc, để Cài đặt không ghi "Linh" khi máy chưa cài hoặc đã có gói Nâng cao. */
    fun describeVietnameseVoice(): String {
        val voice = bestVoice(IosSpeechLanguage.VIETNAMESE, wantMale = false) ?: return "Chưa cài giọng tiếng Việt"
        val quality = when (voice.quality) {
            3L -> " Cao cấp"
            2L -> " Nâng cao"
            else -> ""
        }
        return "${voice.name}$quality (Apple ${voice.language})"
    }
}
