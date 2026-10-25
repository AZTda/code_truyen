package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.shared.tts.TtsSpeedPresets
import kotlin.math.abs

/** Tốc độ đọc: hàng chip theo các mốc [TtsSpeedPresets] (cùng mốc với nút đổi tốc độ trên thanh nghe đọc). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudiobookSpeedSection(
    speed: Float,
    onSelectSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    ReaderSettingBlock(
        label = "Tốc độ đọc",
        trailing = TtsSpeedPresets.label(speed),
        modifier = modifier.padding(horizontal = ReaderSheetInset)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TtsSpeedPresets.PRESETS.forEach { preset ->
                FilterChip8(
                    label = TtsSpeedPresets.label(preset),
                    selected = abs(speed - preset) < 0.05f,
                    onClick = { onSelectSpeed(preset) }
                )
            }
        }
    }
}
