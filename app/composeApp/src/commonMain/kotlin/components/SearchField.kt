package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Ô tìm kiếm kiểu iOS: nền thẻ bo 12, kính lúp, nút xóa nội dung khi đã gõ, và chữ "Hủy" bên phải khi truyền [onCancel].
 * Phím Enter trên bàn phím là "Tìm" và gọi [onSearch]. [requestFocusOnShow] mở bàn phím ngay khi ô xuất hiện.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Tìm kiếm",
    onSearch: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    requestFocusOnShow: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    val colors = LocalCustomColors.current
    LaunchedEffect(requestFocusOnShow) {
        // Ô chưa gắn vào cây giao diện ở khung hình đầu thì requestFocus ném lỗi: bỏ qua, người dùng chạm vào ô là được
        if (requestFocusOnShow) runCatching { focusRequester.requestFocus() }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(InputShape)
                .background(colors.card)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(TruyenCloudIcons.Search, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = AppText.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = placeholder },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(text = placeholder, style = AppText.body, color = colors.textTertiary, maxLines = 1)
                        }
                        inner()
                    }
                }
            )
            if (value.isNotEmpty()) {
                QuietIconButton(
                    icon = TruyenCloudIcons.Clear,
                    contentDescription = "Xóa nội dung tìm kiếm",
                    onClick = { onValueChange("") },
                    tint = colors.textTertiary,
                    iconSize = 20.dp
                )
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
        if (onCancel != null) {
            TextActionButton(text = "Hủy", onClick = onCancel)
        }
    }
}
