package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.shared.tts.AmbientSoundType
import com.truyencloud.shared.tts.TtsVoiceCatalog
import com.truyencloud.shared.tts.SleepTimerMode
import com.truyencloud.shared.tts.TtsVoiceProfile

/**
 * Cài đặt nghe đọc (mở từ thanh nghe đọc): hẹn giờ tắt, tốc độ, âm thanh nền và giọng đọc. Cửa sổ riêng (có nút Back hệ thống,
 * vuốt xuống để đóng) cùng khung với các tờ trượt của màn đọc. Phần giọng đọc dài nhất nên nằm cuối; cả tờ cuộn dọc.
 */
@Composable
fun AudiobookStudioSheet(
    sleepTimerMode: SleepTimerMode,
    sleepTimerRemainingSeconds: Int?,
    ambientSoundType: AmbientSoundType,
    ambientSoundVolume: Float,
    speed: Float,
    voiceProfileId: String = "my_duyen",
    voiceProfiles: List<TtsVoiceProfile> = TtsVoiceCatalog.voices,
    onSelectSleepTimer: (SleepTimerMode) -> Unit,
    onSelectAmbientSound: (AmbientSoundType) -> Unit,
    onChangeAmbientVolume: (Float) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    isOnlineMode: Boolean = true,
    onToggleOnlineMode: (Boolean) -> Unit = {},
    onSelectVoiceProfile: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    ReaderModalSheet(
        onDismiss = onDismiss,
        title = "Nghe đọc truyện",
        subtitle = "Hẹn giờ, tốc độ, âm nền và giọng đọc"
    ) {
        AudiobookStudioBody(
            sleepTimerMode, sleepTimerRemainingSeconds, ambientSoundType, ambientSoundVolume, speed,
            voiceProfileId, voiceProfiles, onSelectSleepTimer, onSelectAmbientSound, onChangeAmbientVolume,
            onSelectSpeed, isOnlineMode, onToggleOnlineMode, onSelectVoiceProfile
        )
    }
}

/** Phần thân cuộn được của tờ cài đặt nghe đọc (đặt dưới hàng tiêu đề của [ReaderModalSheet]). */
@Composable
internal fun ColumnScope.AudiobookStudioBody(
    sleepTimerMode: SleepTimerMode,
    sleepTimerRemainingSeconds: Int?,
    ambientSoundType: AmbientSoundType,
    ambientSoundVolume: Float,
    speed: Float,
    voiceProfileId: String,
    voiceProfiles: List<TtsVoiceProfile>,
    onSelectSleepTimer: (SleepTimerMode) -> Unit,
    onSelectAmbientSound: (AmbientSoundType) -> Unit,
    onChangeAmbientVolume: (Float) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    isOnlineMode: Boolean,
    onToggleOnlineMode: (Boolean) -> Unit,
    onSelectVoiceProfile: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        AudiobookSleepTimerSection(
            sleepTimerMode = sleepTimerMode,
            sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
            onSelectSleepTimer = onSelectSleepTimer
        )
        AudiobookSpeedSection(speed = speed, onSelectSpeed = onSelectSpeed)
        AudiobookAmbientSoundSection(
            ambientSoundType = ambientSoundType,
            ambientSoundVolume = ambientSoundVolume,
            onSelectAmbientSound = onSelectAmbientSound,
            onChangeAmbientVolume = onChangeAmbientVolume
        )
        AudiobookVoiceCatalogSection(
            isOnlineMode = isOnlineMode,
            onToggleOnlineMode = onToggleOnlineMode,
            voiceProfileId = voiceProfileId,
            voiceProfiles = voiceProfiles,
            onSelectVoiceProfile = onSelectVoiceProfile
        )
    }
}
