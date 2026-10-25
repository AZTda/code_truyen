package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Nút "Quét chương mới" ở góc phải thanh trên: biểu tượng làm mới; trong lúc quét đổi thành vòng quay nhỏ cùng ô chạm 48dp
 * (không bấm lại được). Kết quả quét hiện ở khối thông báo của trang ([DetailNotices]).
 */
@Composable
fun ScanChaptersAction(
    isChecking: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isChecking) {
        val colors = LocalCustomColors.current
        Box(
            modifier = modifier.size(48.dp).semantics { contentDescription = "Đang quét chương mới" },
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = colors.accent,
                trackColor = colors.hairline,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
        }
    } else {
        QuietIconButton(
            icon = TruyenCloudIcons.Refresh,
            contentDescription = "Quét chương mới",
            onClick = onClick,
            modifier = modifier
        )
    }
}
