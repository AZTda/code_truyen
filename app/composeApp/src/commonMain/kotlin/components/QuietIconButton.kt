package com.truyencloud.compose.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Nút biểu tượng yên tĩnh: icon 24dp màu chữ phụ, vùng chạm 48dp. [contentDescription] bắt buộc để đọc màn hình
 * (TalkBack / VoiceOver) biết nút làm gì; chỉ truyền chuỗi rỗng khi biểu tượng thật sự trang trí.
 */
@Composable
fun QuietIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalCustomColors.current.textSecondary,
    enabled: Boolean = true,
    iconSize: Dp = 24.dp
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(iconSize)
        )
    }
}
