package com.truyencloud.compose.components

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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ButtonShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Nút phụ dạng tô nhạt: nền accentSoft, chữ màu nhấn, cùng kích thước [PrimaryButton] (50dp, bo 12). Dùng cho hành động
 * thứ hai cạnh nút chính ("Nạp tệp sách" cạnh "Khám phá truyện"). [destructive] đổi sang tông lỗi.
 */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null,
    destructive: Boolean = false
) {
    val colors = LocalCustomColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val soft = if (destructive) colors.errorSoft else colors.accentSoft
    val text0 = if (destructive) colors.error else colors.onAccentSoft
    // Nền tô nhạt có alpha: nhấn xuống thì chồng thêm một lớp để đậm lên
    val container = when {
        !enabled -> colors.cardHigh
        pressed -> soft.compositeOver(soft)
        else -> soft
    }
    val content = if (enabled) text0 else colors.textTertiary

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
