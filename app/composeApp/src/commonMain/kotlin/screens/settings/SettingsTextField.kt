package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Ô nhập dùng trong hộp thoại và tờ trượt của Cài đặt (mật khẩu, địa chỉ máy chủ, hồ sơ, chỉ dẫn dịch): nhãn 13sp phía trên,
 * ô bo 12 có viền [outline][com.truyencloud.compose.theme.AppPalette.outline] (đạt 3:1), viền chuyển màu nhấn khi đang gõ
 * và màu lỗi khi [isError]. Cao tối thiểu 48dp, tự cao thêm theo cỡ chữ; ô mật khẩu có nút hiện / ẩn 48dp.
 *
 * @param label để trống thì không vẽ nhãn (khi tiêu đề đã nằm ở hàng cha); gợi ý [placeholder] khi đó làm mô tả cho TalkBack.
 * @param containerColor nền ô; mặc định màu thẻ. Truyền cardHigh khi ô nằm trên nền thẻ (tờ trượt).
 */
@Composable
fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isPassword: Boolean = false,
    keyboardType: KeyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    singleLine: Boolean = true,
    minHeight: Dp = 48.dp,
    enabled: Boolean = true,
    isError: Boolean = false,
    containerColor: Color = Color.Unspecified
) {
    val colors = LocalCustomColors.current
    var revealed by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        isError -> colors.error
        focused -> colors.accent
        else -> colors.outline
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = AppText.secondaryStrong,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f)
                .heightIn(min = minHeight)
                .clip(InputShape)
                .background(containerColor.takeOrElse { colors.card }, InputShape)
                .border(if (focused || isError) 2.dp else 1.dp, borderColor, InputShape)
                .padding(start = 12.dp, end = if (isPassword) 0.dp else 12.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
        ) {
            Box(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(text = placeholder, style = AppText.body, color = colors.textTertiary)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    textStyle = AppText.body.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = if (isPassword && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                    // Chỉ ghi đè hành vi mặc định khi có việc cần làm: nếu không, nút "Tiếp" của bàn phím
                    // mới chuyển được con trỏ sang ô kế tiếp.
                    keyboardActions = if (onImeAction != null) KeyboardActions(onAny = { onImeAction() }) else KeyboardActions.Default,
                    interactionSource = interaction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { if (label.isEmpty() && placeholder.isNotEmpty()) contentDescription = placeholder }
                )
            }
            if (isPassword) {
                QuietIconButton(
                    icon = if (revealed) TruyenCloudIcons.Visibility else TruyenCloudIcons.VisibilityOff,
                    contentDescription = if (revealed) "Ẩn mật khẩu" else "Hiện mật khẩu",
                    onClick = { revealed = !revealed },
                    // Nút hiện/ẩn không được chen vào thứ tự tab: nếu không, nút "Tiếp" của bàn phím nhảy vào
                    // biểu tượng con mắt thay vì ô mật khẩu kế tiếp
                    modifier = Modifier.focusProperties { canFocus = false },
                    iconSize = 20.dp
                )
            }
        }
    }
}
