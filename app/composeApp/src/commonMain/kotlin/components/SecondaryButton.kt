package com.truyencloud.compose.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ButtonShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Nút phụ viền mảnh 1dp (màu outline, đạt 3:1) trên nền trong suốt: Hủy, Để sau, Giữ lại. Cao tối thiểu 50dp, bo 12.
 * Chữ màu [accent] (mặc định chữ chính; truyền màu lỗi cho hành động xoá). Hành động chính dùng [PrimaryButton],
 * hành động thứ hai nhẹ hơn dùng [TonalButton].
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    val colors = LocalCustomColors.current
    val textColor = if (enabled) accent.takeOrElse { colors.textPrimary } else colors.textTertiary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(ButtonShape)
            .border(1.dp, colors.outline, ButtonShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = AppText.button,
            color = textColor,
            textAlign = TextAlign.Center
        )
    }
}
