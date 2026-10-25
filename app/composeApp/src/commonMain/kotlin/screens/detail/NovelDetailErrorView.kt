package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons

/**
 * Lỗi nạp mục lục: trạng thái trống chuẩn (biểu tượng viền, tiêu đề, một câu, nút). Một nút chính "Thử lại" và nút chữ
 * "Quay lại". Đặt trong khung cao do nơi gọi truyền qua [modifier] để nội dung nằm giữa phần còn lại của màn hình.
 */
@Composable
fun NovelDetailErrorView(
    errorMessage: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        EmptyState(
            message = "Không thể tải danh sách chương",
            subMessage = errorMessage ?: "Vui lòng kiểm tra kết nối mạng và thử lại.",
            icon = TruyenCloudIcons.CloudOff,
            actionButton = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PrimaryButton(text = "Thử lại", onClick = onRetry, leadingIcon = TruyenCloudIcons.Refresh)
                    TextActionButton(text = "Quay lại", onClick = onBack)
                }
            }
        )
    }
}
