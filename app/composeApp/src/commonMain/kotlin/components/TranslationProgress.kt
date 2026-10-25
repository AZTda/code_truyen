package com.truyencloud.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import kotlinx.coroutines.delay

/** Số giây đã trôi qua kể từ lúc composable này xuất hiện (cập nhật mỗi giây). */
@Composable
fun rememberElapsedSeconds(): Int {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            seconds++
        }
    }
    return seconds
}

/** "Đã chờ 23 giây" / "Đã chờ 1 phút 05 giây" — chuỗi hiển thị thời gian chờ dịch. */
fun elapsedWaitText(seconds: Int): String = when {
    seconds < 60 -> "Đã chờ $seconds giây"
    else -> "Đã chờ ${seconds / 60} phút ${(seconds % 60).toString().padStart(2, '0')} giây"
}

/**
 * Thẻ tiến trình dịch chính giữa màn hình khi CHƯA có chữ nào để đọc (lần mở đầu của chương): hiện số giây đã chờ,
 * có nút [Hủy] và [Đọc bản gốc]. Không phủ kín màn hình nên thanh trên/dưới vẫn dùng được.
 */
@Composable
fun TranslationProgressCard(
    statusText: String,
    onCancel: () -> Unit,
    onReadOriginal: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val elapsed = rememberElapsedSeconds()

    Box(modifier = modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        FlatCard(
            modifier = Modifier.fillMaxWidth(0.92f),
            outlined = true,
            contentPadding = PaddingValues(Spacing.xl)
        ) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = colors.accent,
                    trackColor = colors.hairline,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                Text(
                    text = statusText,
                    style = AppText.body,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = elapsedWaitText(elapsed),
                    style = AppText.secondary,
                    color = colors.textSecondary
                )
                if (elapsed >= 30) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "Chương dài hoặc máy chủ đông có thể mất 1–2 phút.",
                        style = AppText.captionRegular,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.lg))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalButton("Hủy", onClick = onCancel, modifier = Modifier.weight(1f))
                    if (onReadOriginal != null) {
                        PrimaryButton("Đọc bản gốc", onClick = onReadOriginal, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
