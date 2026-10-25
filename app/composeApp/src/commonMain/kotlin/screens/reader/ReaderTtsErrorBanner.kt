package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.NoticeTone

/**
 * Thông báo ngắn phía trên thanh nghe đọc: phiên đọc bị dừng vì lỗi / chương chưa dịch / hết truyện. Dùng chung khung banner của
 * màn đọc (nền thẻ đặc, biểu tượng + chữ) nên chữ phía sau không lộ qua. Lề ngang 8dp khớp với [TtsControlBar].
 */
@Composable
internal fun TtsErrorBanner(message: String, modifier: Modifier = Modifier) {
    ReaderBannerCard(
        message = message,
        tone = NoticeTone.Warning,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
