package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors

/** Ô nhập của luyện tập / luyện câu: viền `outline` 1dp (đổi sang màu nhấn khi đang gõ), chữ 15sp, gợi ý màu thứ ba. */
@Composable
internal fun VocabularyTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minHeight: Dp = 52.dp,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    val c = LocalCustomColors.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(InputShape)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) c.accent else c.outline, InputShape)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = AppText.body.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = keyboardOptions,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(text = placeholder, style = AppText.body, color = c.textTertiary)
                    }
                    inner()
                }
            }
        )
    }
}
