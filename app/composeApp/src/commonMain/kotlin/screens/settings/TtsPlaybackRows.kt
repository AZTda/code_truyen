package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.InsetSwitchRow
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.tts.TtsPlatform
import kotlin.math.abs

private val SPEED_OPTIONS = listOf(0.8f to "0.8x", 1.0f to "1.0x", 1.2f to "1.2x", 1.5f to "1.5x", 1.8f to "1.8x", 2.0f to "2.0x")

/** Tốc độ đọc, nghe thử và ba công tắc phát (tự cuộn, tự sang chương, phát ngầm). */
@Composable
internal fun TtsPlaybackRows(
    ttsSpeed: Float,
    ttsAutoScroll: Boolean,
    ttsAutoNextChapter: Boolean,
    ttsBackgroundPlayback: Boolean,
    ttsOnlineMode: Boolean,
    voiceName: String,
    isTesting: Boolean,
    statusMessage: String,
    platform: TtsPlatform,
    onSpeedChange: (Float) -> Unit,
    onAutoScrollChange: (Boolean) -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
    onBackgroundPlaybackChange: (Boolean) -> Unit,
    onToggleTest: () -> Unit
) {
    val colors = LocalCustomColors.current

    SettingsFormRow(title = "Tốc độ đọc", icon = TruyenCloudIcons.Timer, iconTint = InsetIconTint.Neutral) {
        // Ba chip mỗi hàng: 0.8x 1.0x 1.2x / 1.5x 1.8x 2.0x (chữ lớn thì tự xuống thêm hàng)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
            SPEED_OPTIONS.forEach { (speed, label) ->
                FilterChip8(
                    label = label,
                    selected = abs(ttsSpeed - speed) < 0.05f,
                    onClick = { onSpeedChange(speed) },
                    containerColor = colors.cardHigh
                )
            }
        }
    }

    InsetRow(
        title = when {
            isTesting -> "Dừng nghe thử giọng đọc"
            ttsOnlineMode -> "Nghe thử giọng AI mẫu ($voiceName)"
            else -> "Nghe thử giọng máy (${if (platform == TtsPlatform.IOS) "Apple Speech" else "Android TTS"})"
        },
        subtitle = statusMessage.ifBlank { null },
        icon = if (isTesting) TruyenCloudIcons.Stop else TruyenCloudIcons.PlayArrow,
        iconTint = if (isTesting) InsetIconTint.Error else InsetIconTint.Accent,
        onClick = onToggleTest
    )

    InsetSwitchRow(
        title = "Tự động cuộn theo giọng đọc",
        subtitle = "Trang sách tự trượt theo vị trí đoạn văn đang phát",
        checked = ttsAutoScroll,
        onCheckedChange = onAutoScrollChange,
        icon = TruyenCloudIcons.AutoScroll,
        iconTint = InsetIconTint.Neutral
    )
    InsetSwitchRow(
        title = "Tự động đọc tiếp chương sau",
        subtitle = "Tự động tải và phát âm chương mới khi kết thúc chương",
        checked = ttsAutoNextChapter,
        onCheckedChange = onAutoNextChange,
        icon = TruyenCloudIcons.SkipNext,
        iconTint = InsetIconTint.Neutral
    )
    InsetSwitchRow(
        title = "Phát ngầm khi tắt màn hình",
        subtitle = "Tiếp tục đọc khi khóa máy hoặc chuyển sang ứng dụng khác",
        checked = ttsBackgroundPlayback,
        onCheckedChange = onBackgroundPlaybackChange,
        icon = TruyenCloudIcons.Headphones,
        iconTint = InsetIconTint.Neutral
    )
}
