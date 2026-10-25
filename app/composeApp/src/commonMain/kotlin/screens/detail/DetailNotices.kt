package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/** Thông báo của ViewModel bắt đầu bằng "Lỗi" là lỗi; còn lại là kết quả bình thường. */
internal fun isErrorNotice(message: String): Boolean = message.startsWith("Lỗi", ignoreCase = true)

/** Có thông báo nào cần hiện không (để trang bỏ hẳn khối này, khỏi chừa khoảng trống). */
internal fun hasDetailNotices(restored: String?, check: String?, library: String?): Boolean =
    restored != null || check != null || library != null

/**
 * Các thông báo ngắn của trang truyện: lấy lại tiến độ từ máy chủ và kết quả quét chương (có nút đóng), cùng dòng xác nhận
 * tủ sách (tự biến mất sau 3 giây nên chỉ là một dòng chữ nhỏ, không phải dải màu).
 */
@Composable
fun DetailNotices(
    restoredMessage: String?,
    checkMessage: String?,
    libraryMessage: String?,
    onDismissRestored: () -> Unit,
    onDismissCheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (restoredMessage != null) {
            NoticeBanner(text = restoredMessage, tone = NoticeTone.Info, onDismiss = onDismissRestored)
        }
        if (checkMessage != null) {
            NoticeBanner(
                text = checkMessage,
                tone = if (isErrorNotice(checkMessage)) NoticeTone.Error else NoticeTone.Success,
                onDismiss = onDismissCheck
            )
        }
        if (libraryMessage != null) {
            Text(
                text = libraryMessage,
                style = AppText.secondary,
                color = if (isErrorNotice(libraryMessage)) colors.error else colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
