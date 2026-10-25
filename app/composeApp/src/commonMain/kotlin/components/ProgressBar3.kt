package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Thanh tiến độ mảnh 3dp: màu nhấn trên nền đường kẻ. [progress] 0..1; [minVisible] > 0 để đã đọc chút nào thì
 * vẫn thấy một vệt nhỏ (vd. 0.04f) khi progress > 0.
 */
@Composable
fun ProgressBar3(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = LocalCustomColors.current.accent,
    trackColor: Color = LocalCustomColors.current.hairline,
    height: Dp = 3.dp,
    minVisible: Float = 0f
) {
    val p = progress.coerceIn(0f, 1f)
    val shown = if (p > 0f) p.coerceAtLeast(minVisible) else 0f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(trackColor)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f) }
    ) {
        if (shown > 0f) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(color))
        }
    }
}
