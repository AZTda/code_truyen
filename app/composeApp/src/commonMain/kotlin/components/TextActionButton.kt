package com.truyencloud.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Nút chữ không nền cho hành động thứ ba ("Tiếp tục với tư cách khách", "Hủy", "Xem thêm"): chữ màu nhấn 15sp Medium,
 * vùng chạm cao tối thiểu 48dp. [color] chỉ đặt khi cần khác màu nhấn; [destructive] dùng màu lỗi.
 */
@Composable
fun TextActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    color: Color = Color.Unspecified
) {
    val colors = LocalCustomColors.current
    val tint = color.takeOrElse { if (destructive) colors.error else colors.accent }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = AppText.body.copy(fontWeight = FontWeight.Medium),
            color = if (enabled) tint else colors.textTertiary,
            textAlign = TextAlign.Center
        )
    }
}
