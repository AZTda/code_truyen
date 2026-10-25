package com.truyencloud.compose.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.screens.reader.TtsVoiceChoices
import com.truyencloud.shared.tts.TtsVoiceCatalog
import com.truyencloud.shared.tts.TtsPlatform
import com.truyencloud.shared.tts.TtsVoiceProfile

/**
 * Nhóm "Giọng đọc": nguồn giọng (AI hay máy), chọn giọng, tốc độ, nghe thử và các công tắc phát. Danh sách giọng AI mở trong
 * một tờ trượt ([TtsVoicePickerSheet]) để trang Cài đặt không dài ra.
 */
@Composable
fun TtsVoiceSection(
    ttsSpeed: Float,
    ttsAutoScroll: Boolean,
    ttsVoiceTone: String = "",
    ttsVoiceProfileId: String = "my_duyen",
    voiceProfiles: List<TtsVoiceProfile> = TtsVoiceCatalog.voices,
    ttsAutoNextChapter: Boolean,
    ttsBackgroundPlayback: Boolean = true,
    ttsOnlineMode: Boolean = true,
    isTesting: Boolean,
    statusMessage: String,
    platform: TtsPlatform = TtsPlatform.ANDROID,
    offlineEngineName: String = "Android Text-to-Speech (Google / Samsung)",
    offlineVoiceName: String = "Tiếng Việt (vi-VN)",
    offlineSetupGuide: String = "",
    onSpeedChange: (Float) -> Unit,
    onAutoScrollChange: (Boolean) -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
    onBackgroundPlaybackChange: (Boolean) -> Unit = {},
    onOnlineModeChange: (Boolean) -> Unit = {},
    onVoiceProfileChange: (String) -> Unit = {},
    onToggleTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pickerOpen by remember { mutableStateOf(false) }
    // Đúng giọng mà bộ đọc đang dùng, suy từ id (id hỏng mới dùng tới âm sắc đã lưu)
    val currentVoice = TtsVoiceChoices.active(ttsVoiceProfileId, ttsVoiceTone)
    // Giọng AI: tên giọng máy chủ thật sự đọc (hồ sơ cũ -> giọng mặc định cùng giới tính); giọng máy: tên hồ sơ
    val voiceName = if (ttsOnlineMode) TtsVoiceChoices.heard(ttsVoiceProfileId, ttsVoiceTone).name else currentVoice.name

    InsetGroup(
        modifier = modifier,
        header = "Giọng đọc",
        footer = if (!ttsOnlineMode && offlineSetupGuide.isNotBlank()) {
            val platformName = if (platform == TtsPlatform.IOS) "Apple iOS" else "Android TTS"
            "Mẹo nâng cao chất lượng giọng đọc $platformName: $offlineSetupGuide"
        } else null
    ) {
        TtsSourceRow(ttsOnlineMode, platform, onOnlineModeChange)

        if (ttsOnlineMode) {
            TtsOnlineVoiceRow(voiceName, onOpenPicker = { pickerOpen = true })
        } else {
            TtsOfflineRows(offlineEngineName, offlineVoiceName, currentVoice, onVoiceProfileChange)
        }

        TtsPlaybackRows(
            ttsSpeed = ttsSpeed,
            ttsAutoScroll = ttsAutoScroll,
            ttsAutoNextChapter = ttsAutoNextChapter,
            ttsBackgroundPlayback = ttsBackgroundPlayback,
            ttsOnlineMode = ttsOnlineMode,
            voiceName = voiceName,
            isTesting = isTesting,
            statusMessage = statusMessage,
            platform = platform,
            onSpeedChange = onSpeedChange,
            onAutoScrollChange = onAutoScrollChange,
            onAutoNextChange = onAutoNextChange,
            onBackgroundPlaybackChange = onBackgroundPlaybackChange,
            onToggleTest = onToggleTest
        )
    }

    if (pickerOpen) {
        TtsVoicePickerSheet(
            voiceProfiles = voiceProfiles,
            ttsVoiceProfileId = ttsVoiceProfileId,
            ttsVoiceTone = ttsVoiceTone,
            onVoiceProfileChange = onVoiceProfileChange,
            onDismiss = { pickerOpen = false }
        )
    }
}
