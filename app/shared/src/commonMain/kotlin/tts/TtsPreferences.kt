package com.truyencloud.shared.tts

import com.truyencloud.shared.data.UserProfile
import com.truyencloud.shared.repo.UserRepository

/**
 * Chỉ những trường của hồ sơ mà bộ máy đọc cần. Đổi cỡ chữ / chủ đề không đổi
 * bộ giá trị này, nên `distinctUntilChanged()` chặn được việc đẩy lại cài đặt TTS
 * (trước đây làm câu đang đọc bị đọc lại và giọng bị trả về giá trị cũ).
 */
data class TtsPlaybackSettings(
    val serverUrl: String,
    val onlineMode: Boolean,
    val backgroundPlayback: Boolean,
    val voiceProfileId: String,
    val speed: Float
)

fun UserProfile.ttsPlaybackSettings(): TtsPlaybackSettings = TtsPlaybackSettings(
    serverUrl = serverUrl,
    onlineMode = ttsOnlineMode,
    backgroundPlayback = ttsBackgroundPlayback,
    voiceProfileId = ttsVoiceProfileId,
    speed = ttsSpeed
)

fun TtsSpeaker.applyPlaybackSettings(s: TtsPlaybackSettings) {
    setServerUrl(s.serverUrl)
    setOnlineMode(s.onlineMode)
    setBackgroundPlayback(s.backgroundPlayback)
    setVoiceProfile(s.voiceProfileId)
    setSpeed(s.speed)
}

/**
 * Ghi cài đặt TTS, GIỮ NGUYÊN mọi trường không truyền vào.
 *
 * `updateTtsPreferences` từng có giá trị mặc định cứng cho giọng/chế độ online, nên gọi thiếu
 * tham số (như khi chỉ đổi tốc độ) âm thầm đặt lại giọng về "mai_phuong".
 * Chỉ truyền [voiceTone] (không truyền id) thì id đi theo âm sắc đó, không bị âm sắc cũ của id đang lưu thắng.
 */
fun UserRepository.patchTtsPreferences(
    speed: Float = profile.value.ttsSpeed,
    autoScroll: Boolean = profile.value.ttsAutoScroll,
    voiceTone: String = profile.value.ttsVoiceTone,
    voiceProfileId: String = if (voiceTone == profile.value.ttsVoiceTone) profile.value.ttsVoiceProfileId
    else TtsVoiceResolver.profileFor(voiceTone).id,
    autoNext: Boolean = profile.value.ttsAutoNextChapter,
    backgroundPlayback: Boolean = profile.value.ttsBackgroundPlayback,
    onlineMode: Boolean = profile.value.ttsOnlineMode
) {
    updateTtsPreferences(
        speed = speed,
        autoScroll = autoScroll,
        voiceTone = voiceTone,
        voiceProfileId = voiceProfileId,
        autoNext = autoNext,
        backgroundPlayback = backgroundPlayback,
        onlineMode = onlineMode
    )
}
