package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.repo.UserRepository
import com.truyencloud.shared.tts.TtsSpeaker
import com.truyencloud.shared.tts.TtsVoiceCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Chép trạng thái "thụ động" của bộ đọc (tốc độ, hẹn giờ ngủ, âm nền, giọng, chế độ online) và cài đặt TTS trong hồ sơ vào
 * [ReaderUiState] để giao diện hiển thị. Phần có logic (đang phát, đoạn đang nghe, lỗi, chuyển chương) ở [ReaderTtsCoordinator].
 */
internal class ReaderTtsStateMirror(
    private val ttsSpeaker: TtsSpeaker,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val userRepository: UserRepository?
) {
    private var state: ReaderUiState
        get() = uiStateFlow.value
        set(value) { uiStateFlow.value = value }

    fun start() {
        scope.launch { ttsSpeaker.speed.collect { state = state.copy(ttsSpeed = it) } }
        scope.launch { ttsSpeaker.sleepTimerMode.collect { state = state.copy(sleepTimerMode = it) } }
        scope.launch { ttsSpeaker.sleepTimerRemainingSeconds.collect { state = state.copy(sleepTimerRemainingSeconds = it) } }
        scope.launch { ttsSpeaker.ambientSoundType.collect { state = state.copy(ambientSoundType = it) } }
        scope.launch { ttsSpeaker.ambientSoundVolume.collect { state = state.copy(ambientSoundVolume = it) } }
        scope.launch {
            ttsSpeaker.currentVoiceProfile.collect { prof ->
                state = state.copy(ttsVoiceProfileId = prof.id, ttsVoiceTone = prof.name)
            }
        }
        scope.launch { ttsSpeaker.isOnlineMode.collect { state = state.copy(ttsOnlineMode = it) } }
        scope.launch { ttsSpeaker.isDeviceVoiceFallback.collect { state = state.copy(ttsDeviceFallback = it) } }
        // Danh mục giọng AI của máy chủ đang dùng (tải lúc chạy; máy chủ đổi mô hình không cần bản app mới)
        scope.launch { TtsVoiceCatalog.active.collect { state = state.copy(voiceProfiles = it.voices) } }
        userRepository?.let { repo ->
            scope.launch {
                repo.profile.map { it.ttsAutoScroll to it.ttsAutoNextChapter }.distinctUntilChanged()
                    .collect { (autoScroll, autoNext) ->
                        state = state.copy(ttsAutoScroll = autoScroll, ttsAutoNextChapter = autoNext)
                    }
            }
        }
    }
}
