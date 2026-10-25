package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/** Khối chờ khi đang nạp mục lục lần đầu: vòng quay nhỏ cùng hai dòng chữ, không khung, không viền. */
@Composable
fun DetailLoadingCard(modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageH, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        CircularProgressIndicator(
            color = colors.accent,
            trackColor = colors.hairline,
            strokeWidth = 3.dp,
            modifier = Modifier.size(32.dp)
        )
        Text(
            text = "Đang kết nối nguồn truyện và nạp mục lục...",
            style = AppText.body,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Bạn có thể thêm truyện vào Tủ sách sau khi mục lục hiện ra",
            style = AppText.secondary,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

/** Dải thông báo chế độ ngoại tuyến. */
@Composable
fun DetailOfflineBanner(modifier: Modifier = Modifier) {
    NoticeBanner(
        text = "Chế độ ngoại tuyến: bạn đang đọc các chương đã lưu trong máy.",
        modifier = modifier,
        tone = NoticeTone.Warning
    )
}
