package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.UIFont

/**
 * Ô nhập trong sheet / hộp thoại của màn đọc: nền thẻ bo 12, viền 1dp màu outline (>= 3:1), viền màu nhấn 2dp khi đang gõ và màu lỗi
 * khi [isError]. [containerColor] đổi sang cardHigh khi ô nằm trong một thẻ. [fontFamily] chỉ đặt serif cho ô nhập chữ Hán / tên trong
 * truyện. [clearable] hiện nút xóa nội dung (48dp) khi ô có chữ.
 */
@Composable
internal fun ReaderInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Dp = 48.dp,
    maxLines: Int = if (singleLine) 1 else 6,
    fontFamily: FontFamily = UIFont,
    containerColor: Color = Color.Unspecified,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    clearable: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = LocalCustomColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val showClear = clearable && value.isNotEmpty() && enabled
    // Ô một dòng: chiều cao do minHeight quyết định, nút xóa 48dp chạm sát mép phải
    val endPadding = if (showClear || trailing != null) 0.dp else 12.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(InputShape)
            .background(containerColor.takeOrElse { colors.card })
            .border(
                width = if (focused || isError) 2.dp else 1.dp,
                color = when {
                    isError -> colors.error
                    focused -> colors.accent
                    else -> colors.outline
                },
                shape = InputShape
            )
            .padding(start = 12.dp, end = endPadding, top = if (singleLine) 0.dp else 12.dp, bottom = if (singleLine) 0.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            maxLines = maxLines,
            textStyle = AppText.body.copy(color = colors.textPrimary, fontFamily = fontFamily),
            cursorBrush = SolidColor(colors.accent),
            interactionSource = interaction,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = AppText.body,
                            color = colors.textTertiary,
                            maxLines = if (singleLine) 1 else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            }
        )
        if (showClear) {
            QuietIconButton(TruyenCloudIcons.Clear, "Xóa nội dung", { onValueChange("") }, tint = colors.textTertiary, iconSize = 20.dp)
        }
        trailing?.invoke(this)
    }
}
