package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Các bảng AI (tóm tắt, tra cứu, trắc nghiệm, trợ lý, dấu trang, thuật ngữ) nằm trên nền tờ trượt của CHỦ ĐỀ ĐỌC đang dùng
 * ([readerSheetColor]); màu chữ và màu nhấn lấy từ cùng bảng màu nên sáng / sepia / đen OLED đều đọc được, không ép nền tối.
 */
@Composable
internal fun AiLoadingPanel(text: String, modifier: Modifier = Modifier, height: Dp = 180.dp) {
    val colors = LocalCustomColors.current
    Box(modifier = modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = text, style = AppText.secondary, color = colors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Thẻ báo lỗi của một tính năng AI: tiêu đề + lý do + nút thử lại. Dùng thay cho mọi nội dung "dự phòng" bịa ra — kết quả
 * AI dự phòng của máy chủ không bao giờ được hiện như câu trả lời thật.
 */
@Composable
internal fun AiErrorPanel(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onRetry: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null
) {
    ReaderBannerCard(
        message = detail?.takeIf { it.isNotBlank() } ?: title,
        title = title.takeIf { !detail.isNullOrBlank() },
        tone = NoticeTone.Error,
        actions = listOfNotNull(
            onRetry?.let { BannerAction("Thử lại", primary = true, onClick = it) },
            onClose?.let { BannerAction("Đóng", onClick = it) }
        ),
        modifier = modifier
    )
}

/** Ghi chú trung tính (không phải lỗi), ví dụ "chương đầu tiên chưa có phần trước để nhắc lại". */
@Composable
internal fun AiInfoPanel(message: String, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    ReaderBannerCard(
        message = message,
        tone = NoticeTone.Info,
        actions = listOfNotNull(onClose?.let { BannerAction("Đóng", onClick = it) }),
        modifier = modifier
    )
}
