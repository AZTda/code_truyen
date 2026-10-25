package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.tts.AmbientSoundType

/** Biểu tượng của âm nền (null cho "Tắt"); dùng chung cho chip trong tờ cài đặt và dòng trạng thái trên thanh nghe đọc. */
internal fun AmbientSoundType.icon(): ImageVector? = when (this) {
    AmbientSoundType.NONE -> null
    AmbientSoundType.RAIN -> TruyenCloudIcons.AmbientRain
    AmbientSoundType.FIREPLACE -> TruyenCloudIcons.AmbientFire
    AmbientSoundType.OCEAN -> TruyenCloudIcons.AmbientWave
    AmbientSoundType.CAFE -> TruyenCloudIcons.AmbientCafe
}

/** Âm thanh nền thư giãn: hàng chip chọn loại âm, thêm thanh âm lượng khi đang bật (phần trăm hiện bên phải nhãn khối). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudiobookAmbientSoundSection(
    ambientSoundType: AmbientSoundType,
    ambientSoundVolume: Float,
    onSelectAmbientSound: (AmbientSoundType) -> Unit,
    onChangeAmbientVolume: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val isActive = ambientSoundType != AmbientSoundType.NONE

    ReaderSettingBlock(
        label = "Âm thanh nền",
        trailing = if (isActive) "${(ambientSoundVolume * 100).toInt()}%" else null,
        modifier = modifier.padding(horizontal = ReaderSheetInset)
    ) {
        Column {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AmbientSoundType.entries.forEach { type ->
                    FilterChip8(
                        label = type.label,
                        selected = ambientSoundType == type,
                        onClick = { onSelectAmbientSound(type) },
                        leadingIcon = type.icon()
                    )
                }
            }
            if (isActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(TruyenCloudIcons.VolumeMute, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                    Slider(
                        value = ambientSoundVolume,
                        onValueChange = onChangeAmbientVolume,
                        valueRange = 0.05f..1.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accent,
                            activeTrackColor = colors.accent,
                            inactiveTrackColor = colors.outline.copy(alpha = 0.5f),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .semantics { contentDescription = "Âm lượng âm nền" }
                    )
                    Icon(TruyenCloudIcons.VolumeUp, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
