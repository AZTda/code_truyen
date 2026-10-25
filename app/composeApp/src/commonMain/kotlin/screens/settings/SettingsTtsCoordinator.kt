package com.truyencloud.compose.screens.settings

import com.truyencloud.compose.screens.reader.TtsVoiceChoices
import com.truyencloud.shared.repo.UserRepository
import com.truyencloud.shared.tts.TtsPreviewStatus
import com.truyencloud.shared.tts.TtsSpeaker
import com.truyencloud.shared.tts.TtsVoiceCatalog
import com.truyencloud.shared.tts.TtsVoiceCatalogRepository
import com.truyencloud.shared.tts.TtsVoiceResolver
import com.truyencloud.shared.tts.applyPlaybackSettings
import com.truyencloud.shared.tts.patchTtsPreferences
import com.truyencloud.shared.tts.ttsPlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Cài đặt giọng đọc. Nghe thử chạy trên bộ phát RIÊNG của bộ đọc (`previewVoice`), không
 * chạm vào phiên đọc truyện đang có; việc ghi cài đặt chỉ đổi đúng trường được sửa.
 */
class SettingsTtsCoordinator(
    private val userRepository: UserRepository,
    private val ttsSpeaker: TtsSpeaker,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val catalogRepository: TtsVoiceCatalogRepository = TtsVoiceCatalogRepository.shared
) {

    /** Đồng bộ hai chiều giữa bộ đọc và màn hình Cài đặt. Gọi một lần khi khởi tạo. */
    fun observe(scope: CoroutineScope) {
        // Chỉ đẩy cài đặt vào bộ đọc khi CHÍNH các trường TTS đổi. Trước đây mỗi lần hồ sơ đổi
        // (ví dụ chỉnh cỡ chữ) đều gọi lại các setter, khiến câu đang đọc bị đọc lại từ đầu.
        scope.launch {
            userRepository.profile.map { it.ttsPlaybackSettings() }.distinctUntilChanged()
                .collect { ttsSpeaker.applyPlaybackSettings(it) }
        }
        // Nghe thử chạy trên bộ phát riêng: nút "nghe thử" chỉ phản ánh nghe thử, không phải phiên đọc truyện
        scope.launch {
            var wasPreviewing = false
            ttsSpeaker.isPreviewPlaying.collect { previewing ->
                val s = uiState.value
                val ended = wasPreviewing && !previewing && s.isTtsTesting
                uiState.value = s.copy(
                    isTtsTesting = previewing,
                    // Lượt nghe thử hỏng (máy chủ không phản hồi, bộ máy đọc lỗi...) không được báo "đã xong"
                    ttsStatusMessage = if (ended) TtsPreviewStatus.endMessage(ttsSpeaker.previewError.value) else s.ttsStatusMessage
                )
                wasPreviewing = previewing
            }
        }
        scope.launch {
            // Báo ngay khi nghe thử phải chuyển sang giọng máy giữa chừng (lượt vẫn đang chạy); drop(1) bỏ lỗi cũ của lượt trước
            ttsSpeaker.previewError.drop(1).collect { err ->
                if (err != null) uiState.value = uiState.value.copy(ttsStatusMessage = err)
            }
        }
        scope.launch {
            // drop(1): bỏ giá trị phát lại ngay khi đăng ký (lỗi cũ của phiên trước), chỉ hiện lỗi MỚI
            ttsSpeaker.lastError.drop(1).collect { err ->
                if (err != null) uiState.value = uiState.value.copy(ttsStatusMessage = err)
            }
        }
        scope.launch {
            ttsSpeaker.speed.collect { spd -> uiState.value = uiState.value.copy(ttsSpeed = spd) }
        }
        // Danh mục giọng AI là của MÁY CHỦ đang dùng: đổi địa chỉ thì dùng bản đã lưu của máy chủ đó rồi tải lại
        scope.launch {
            userRepository.profile.map { it.serverUrl }.distinctUntilChanged().collect { url ->
                catalogRepository.applyCached(url)
                catalogRepository.refreshAsync(scope, url, force = true)
            }
        }
        scope.launch { TtsVoiceCatalog.active.collect { uiState.value = uiState.value.copy(voiceProfiles = it.voices) } }
    }

    /** Mở Cài đặt: tải lại danh mục giọng (máy chủ có thể vừa đổi mô hình); gọi liên tục thì tối đa mỗi phút một lần. */
    fun refreshVoiceCatalog(scope: CoroutineScope) = catalogRepository.refreshAsync(scope, userRepository.profile.value.serverUrl)

    fun onTtsSpeedChange(speed: Float) {
        uiState.value = uiState.value.copy(ttsSpeed = speed)
        ttsSpeaker.setSpeed(speed)
        userRepository.patchTtsPreferences(speed = speed)
    }

    fun onTtsAutoScrollChange(enabled: Boolean) {
        uiState.value = uiState.value.copy(ttsAutoScroll = enabled)
        userRepository.patchTtsPreferences(autoScroll = enabled)
    }

    fun onTtsAutoNextChange(enabled: Boolean) {
        uiState.value = uiState.value.copy(ttsAutoNextChapter = enabled)
        userRepository.patchTtsPreferences(autoNext = enabled)
    }

    fun onTtsBackgroundPlaybackChange(enabled: Boolean) {
        uiState.value = uiState.value.copy(ttsBackgroundPlayback = enabled)
        ttsSpeaker.setBackgroundPlayback(enabled)
        userRepository.patchTtsPreferences(backgroundPlayback = enabled)
    }

    fun onTtsOnlineModeChange(enabled: Boolean) {
        uiState.value = uiState.value.copy(
            ttsOnlineMode = enabled,
            ttsStatusMessage = if (enabled) "Đã chuyển sang giọng AI do MiniPC của bạn tạo sẵn" else "Đã chuyển sang giọng đọc có sẵn trên thiết bị"
        )
        ttsSpeaker.setOnlineMode(enabled)
        userRepository.patchTtsPreferences(onlineMode = enabled)
        refreshOfflineVoice()
        if (uiState.value.isTtsTesting) startPreview()
    }

    /**
     * Nhãn bộ máy / giọng máy THẬT của thiết bị. Bộ máy khởi tạo bất đồng bộ và giọng chọn được còn phụ thuộc hồ sơ
     * (nam / nữ), nên đọc lại mỗi lần mở Cài đặt, đổi nguồn, đổi giọng thay vì chụp một lần lúc tạo ViewModel.
     */
    fun refreshOfflineVoice() {
        uiState.value = uiState.value.copy(
            ttsOfflineEngineName = ttsSpeaker.offlineEngineInfo,
            ttsOfflineVoiceName = ttsSpeaker.offlineVoiceName
        )
    }

    fun onTtsVoiceProfileChange(profileId: String) {
        val profile = TtsVoiceResolver.profileFor(profileId)
        uiState.value = uiState.value.copy(ttsVoiceProfileId = profile.id, ttsVoiceTone = profile.name)
        ttsSpeaker.setVoiceProfile(profile.id)
        userRepository.patchTtsPreferences(voiceProfileId = profile.id, voiceTone = profile.name)
        refreshOfflineVoice()
        // Đang nghe thử thì nghe ngay giọng mới
        if (uiState.value.isTtsTesting) startPreview()
    }

    fun testTtsVoice() {
        if (uiState.value.isTtsTesting) {
            ttsSpeaker.stopPreview()
            uiState.value = uiState.value.copy(isTtsTesting = false, ttsStatusMessage = "Đã dừng nghe thử.")
        } else {
            startPreview()
        }
    }

    private fun startPreview() {
        val s = uiState.value
        if (s.ttsOnlineMode) {
            // Cùng giọng với hàng "Giọng đọc" và với bộ đọc; câu mẫu nêu tên và miền của giọng máy chủ thật sự đọc
            val profile = TtsVoiceChoices.active(s.ttsVoiceProfileId, s.ttsVoiceTone)
            ttsSpeaker.previewVoice(
                samples = TtsVoiceChoices.previewSamples(profile),
                profileId = profile.id,
                onlineMode = true,
                speed = s.ttsSpeed
            )
            val heard = TtsVoiceChoices.heard(profile.id)
            showPreviewStatus("Đang nghe thử giọng AI ${heard.name} (${TtsVoiceChoices.subtitle(heard)})...")
        } else {
            // Nhãn là giọng máy THẬT sẽ đọc (hoặc "Chưa cài giọng tiếng Việt"), không phải tên cố định "Linh" / "Google Speech"
            refreshOfflineVoice()
            val voiceLabel = uiState.value.ttsOfflineVoiceName
            ttsSpeaker.previewVoice(
                samples = listOf("Xin chào!", "Đây là giọng đọc có sẵn trên thiết bị của bạn."),
                profileId = TtsVoiceChoices.active(s.ttsVoiceProfileId, s.ttsVoiceTone).id,
                onlineMode = false,
                speed = s.ttsSpeed
            )
            showPreviewStatus("Đang nghe thử giọng máy ngoại tuyến ($voiceLabel)...")
        }
    }

    /**
     * Trạng thái ngay sau khi gọi nghe thử, theo những gì bộ đọc thực sự làm: không phát được thì báo lý do (không để
     * "đang nghe thử" treo mãi); đã phải chuyển sang giọng máy ngay từ đầu thì báo điều đó thay vì lời mặc định.
     */
    private fun showPreviewStatus(startedMessage: String) {
        val started = ttsSpeaker.isPreviewPlaying.value
        val note = ttsSpeaker.previewError.value
        uiState.value = uiState.value.copy(
            isTtsTesting = started,
            ttsStatusMessage = when {
                !started -> note ?: TtsPreviewStatus.FAILED
                note != null -> note
                else -> startedMessage
            }
        )
    }
}
