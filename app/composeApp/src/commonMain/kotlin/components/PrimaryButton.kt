package com.truyencloud.compose.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ButtonShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Nút hành động chính: nền màu nhấn, chữ onAccent, cao tối thiểu 50dp (cao thêm theo cỡ chữ hệ thống), bo 12.
 * Mỗi vùng màn hình chỉ một nút chính. [destructive] đổi sang màu lỗi cho hành động không hoàn tác (Đăng xuất, Xóa).
 * Chiều rộng theo nội dung: thêm `Modifier.fillMaxWidth()` để chiếm cả hàng.
 *
 * @param containerColor / [contentColor] chỉ dùng khi cần màu riêng; mặc định theo chủ đề.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null,
    destructive: Boolean = false,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified
) {
    val colors = LocalCustomColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val base = containerColor.takeOrElse { if (destructive) colors.error else colors.accent }
    val pressedColor = when {
        containerColor.isSpecified -> base.copy(alpha = 0.85f)
        destructive -> colors.error.copy(alpha = 0.85f)
        else -> colors.accentStrong
    }
    val target = when {
        !enabled -> colors.cardHigh
        pressed -> pressedColor
        else -> base
    }
    val container by animateColorAsState(target, tween(AppAnimations.SHORT_MS / 2), label = "primaryButtonFill")
    val content = if (enabled) contentColor.takeOrElse { colors.onAccent } else colors.textTertiary

    Row(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(ButtonShape)
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !isLoading,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text = text, style = AppText.button, color = content, textAlign = TextAlign.Center)
        }
    }
}
