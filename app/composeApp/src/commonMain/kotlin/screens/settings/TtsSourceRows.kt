package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.screens.reader.TtsVoiceChoices
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.tts.TtsPlatform
import com.truyencloud.shared.tts.TtsVoiceProfile
import com.truyencloud.shared.tts.TtsVoiceResolver
import androidx.compose.ui.unit.dp

/** Hàng chọn nguồn giọng đọc: giọng AI từ máy chủ hay giọng có sẵn trên máy. */
@Composable
internal fun TtsSourceRow(
    ttsOnlineMode: Boolean,
    platform: TtsPlatform,
    onOnlineModeChange: (Boolean) -> Unit
) {
    SettingsFormRow(
        title = "Nguồn giọng đọc",
        subtitle = when {
            ttsOnlineMode -> "Giọng AI do MiniPC của bạn tạo sẵn, nghe liền mạch; cần kết nối mạng. Máy chủ lỗi thì đọc bằng giọng của điện thoại."
            platform == TtsPlatform.IOS ->
                "Động cơ Apple Speech tích hợp sẵn trong iOS (giọng Linh vi-VN), hoạt động hoàn toàn ngoại tuyến."
            else ->
                "Động cơ Android TextToSpeech tích hợp sẵn (Google Speech / Samsung), hoạt động không cần kết nối mạng."
        },
        icon = TruyenCloudIcons.Headphones
    ) {
        OnCardTrack {
            SegmentedControl(
                options = listOf("Giọng AI", "Giọng máy"),
                selectedIndex = if (ttsOnlineMode) 0 else 1,
                onSelect = { onOnlineModeChange(it == 0) }
            )
        }
    }
}

/** Giọng AI đang chọn; chạm để mở danh sách giọng. */
@Composable
internal fun TtsOnlineVoiceRow(
    voiceName: String,
    onOpenPicker: () -> Unit
) {
    InsetRow(
        title = "Giọng đọc",
        value = voiceName,
        icon = TruyenCloudIcons.VolumeUp,
        showChevron = true,
        onClick = onOpenPicker
    )
}

/**
 * Động cơ ngoại tuyến của máy và âm sắc (giọng nữ chuẩn / nam trầm / mặc định). Mỗi âm sắc đặt một hồ sơ có đúng giới tính
 * ([TtsVoiceResolver.resolveToneProfileId]) và chip sáng theo âm sắc của hồ sơ mà bộ đọc đang dùng.
 */
@Composable
internal fun TtsOfflineRows(
    offlineEngineName: String,
    offlineVoiceName: String,
    active: TtsVoiceProfile,
    onVoiceProfileChange: (String) -> Unit
) {
    val colors = LocalCustomColors.current
    InsetRow(
        title = offlineEngineName,
        subtitle = "Giọng phát âm: $offlineVoiceName",
        icon = TruyenCloudIcons.Smartphone,
        iconTint = InsetIconTint.Success
    )
    SettingsFormRow(title = "Âm sắc giọng máy", icon = TruyenCloudIcons.Mic, iconTint = InsetIconTint.Neutral) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TtsVoiceResolver.OFFLINE_TONES.forEach { tone ->
                FilterChip8(
                    label = tone,
                    selected = TtsVoiceChoices.isToneSelected(tone, active),
                    onClick = { onVoiceProfileChange(TtsVoiceChoices.profileIdForTone(tone, active)) },
                    containerColor = colors.cardHigh
                )
            }
        }
    }
}
