package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.ProgressBar3
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Phản hồi của tác vụ tải / dịch hàng loạt: thông báo (lỗi / đã xong / đang chạy) và thanh tiến độ mảnh kèm phần trăm.
 * Không còn dải màu xanh cho mọi thông báo — màu theo trạng thái và luôn đi kèm chữ.
 */
@Composable
fun BatchFeedback(
    message: String?,
    progress: Float?,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    if (message == null && progress == null) return
    val colors = LocalCustomColors.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (message != null) {
            NoticeBanner(
                text = message,
                tone = when {
                    isError -> NoticeTone.Error
                    progress != null && progress >= 1f -> NoticeTone.Success
                    else -> NoticeTone.Info
                }
            )
        }
        if (progress != null) {
            val shown = progress.coerceIn(0f, 1f)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProgressBar3(progress = shown, modifier = Modifier.weight(1f), height = 6.dp)
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
}
