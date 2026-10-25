package com.truyencloud.compose.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Hàng nhập liệu trong [InsetGroup] (Đăng nhập, Đổi mật khẩu, Địa chỉ máy chủ): ô biểu tượng tuỳ chọn, chữ 15sp, gợi ý
 * [placeholder] màu thứ ba, nút phụ bên phải (hiện / ẩn mật khẩu). [isError] đổi chữ sang màu lỗi.
 */
@Composable
fun InsetTextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: InsetIconTint = InsetIconTint.Neutral,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = LocalCustomColors.current
    InsetRowFrame(
        modifier = modifier,
        dividerInset = if (icon != null) IconDividerInset else PlainDividerInset,
        enabled = enabled,
        action = null
    ) {
        if (icon != null) {
            InsetIconTile(icon, iconTint, enabled)
            Spacer(Modifier.width(16.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = AppText.body.copy(color = if (isError) colors.error else colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            modifier = Modifier
                .weight(1f)
                .alpha(if (enabled) 1f else 0.5f)
                .semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(text = placeholder, style = AppText.body, color = colors.textTertiary, maxLines = 1)
                    }
                    inner()
                }
            }
        )
        trailing?.invoke(this)
    }
}
