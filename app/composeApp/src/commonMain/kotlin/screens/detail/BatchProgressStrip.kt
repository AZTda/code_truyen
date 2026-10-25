package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.ProgressBar3
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Dải tiến độ tải / dịch hàng loạt trên trang truyện khi tờ "Tải chương về máy" đã đóng: một dòng chữ, thanh mảnh 3dp và
 * phần trăm. Chạm để mở lại tờ xem chi tiết. Trang chỉ hiện dải này khi tác vụ đang chạy (0 <= tiến độ < 1).
 */
@Composable
fun BatchProgressStrip(
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val shown = progress.coerceIn(0f, 1f)
    FlatCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Đang tải chương về máy", style = AppText.secondaryStrong, color = colors.textPrimary)
                ProgressBar3(progress = shown)
            }
            Text(
                text = "${(shown * 100).toInt()}%",
                style = AppText.caption,
                color = colors.textSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 40.dp)
            )
        }
    }
}
