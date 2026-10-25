package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.repo.UserRepository
import com.truyencloud.shared.tts.AmbientSoundType
import com.truyencloud.shared.tts.SleepTimerMode
import com.truyencloud.shared.tts.TtsSpeaker
import com.truyencloud.shared.tts.TtsSpeedPresets
import com.truyencloud.shared.tts.TtsVoiceResolver
import com.truyencloud.shared.tts.patchTtsPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Giọng / tốc độ / chế độ online chọn trong Audiobook Studio: áp vào bộ đọc VÀ ghi vào
 * hồ sơ người dùng. Trước đây chỉ áp vào bộ đọc, nên giọng lưu trong hồ sơ (chọn ở Cài đặt)
 * ghi đè lại lựa chọn của người đang nghe ở lần đẩy cài đặt kế tiếp.
 */
internal class ReaderTtsSettingsActions(
    private val ttsSpeaker: TtsSpeaker,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val userRepository: UserRepository?
) {
    fun openStudio() { uiStateFlow.value = uiStateFlow.value.copy(showAudiobookStudio = true) }
    fun closeStudio() { uiStateFlow.value = uiStateFlow.value.copy(showAudiobookStudio = false) }

    fun setSleepTimer(mode: SleepTimerMode) = ttsSpeaker.setSleepTimer(mode)

    fun setAmbientSound(type: AmbientSoundType) = ttsSpeaker.setAmbientSound(type, uiStateFlow.value.ambientSoundVolume)

    fun setAmbientVolume(volume: Float) {
        uiStateFlow.value = uiStateFlow.value.copy(ambientSoundVolume = volume)
        ttsSpeaker.setAmbientVolume(volume)
    }

    fun setVoiceProfile(profileId: String) {
        // Cùng bộ phân giải với bộ đọc: id lưu vào hồ sơ luôn là id hợp lệ
        val profile = TtsVoiceResolver.profileFor(profileId)
        uiStateFlow.value = uiStateFlow.value.copy(ttsVoiceProfileId = profile.id, ttsVoiceTone = profile.name)
        ttsSpeaker.setVoiceProfile(profile.id)
        userRepository?.patchTtsPreferences(voiceProfileId = profile.id, voiceTone = profile.name)
    }

    fun setSpeed(speed: Float) {
        ttsSpeaker.setSpeed(speed)
        userRepository?.patchTtsPreferences(speed = speed)
    }

    /** Nút đổi tốc độ trên thanh điều khiển: cùng bộ mốc với Audiobook Studio. */
    fun cycleSpeed() = setSpeed(TtsSpeedPresets.next(uiStateFlow.value.ttsSpeed))

    fun setOnlineMode(enabled: Boolean) {
        uiStateFlow.value = uiStateFlow.value.copy(ttsOnlineMode = enabled)
        ttsSpeaker.setOnlineMode(enabled)
        userRepository?.patchTtsPreferences(onlineMode = enabled)
    }
}
