package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.shared.tts.SleepTimerMode

private val TimerOptions = listOf(
    SleepTimerMode.OFF,
    SleepTimerMode.MIN_15,
    SleepTimerMode.MIN_30,
    SleepTimerMode.MIN_45,
    SleepTimerMode.MIN_60,
    SleepTimerMode.END_OF_CHAPTER
)

/** "15p" của bộ đọc -> "15 phút" trên chip; "Tắt" và "Hết chương" giữ nguyên. */
private fun timerLabel(mode: SleepTimerMode): String = if (mode.minutes > 0) "${mode.minutes} phút" else mode.label

/**
 * Hẹn giờ tắt nghe đọc: hàng chip (tắt, 15 / 30 / 45 / 60 phút, hết chương). Bên phải nhãn khối hiện thời gian còn lại
 * khi đang đếm ngược, hoặc "Khi hết chương".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudiobookSleepTimerSection(
    sleepTimerMode: SleepTimerMode,
    sleepTimerRemainingSeconds: Int?,
    onSelectSleepTimer: (SleepTimerMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val status = when {
        sleepTimerRemainingSeconds != null && sleepTimerRemainingSeconds > 0 -> "Còn ${formatClock(sleepTimerRemainingSeconds)}"
        sleepTimerMode == SleepTimerMode.END_OF_CHAPTER -> "Khi hết chương"
        else -> null
    }
    ReaderSettingBlock(
        label = "Hẹn giờ tắt",
        trailing = status,
        modifier = modifier.padding(horizontal = ReaderSheetInset)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimerOptions.forEach { mode ->
                FilterChip8(
                    label = timerLabel(mode),
                    selected = sleepTimerMode == mode,
                    onClick = { onSelectSleepTimer(mode) }
                )
            }
        }
    }
}
