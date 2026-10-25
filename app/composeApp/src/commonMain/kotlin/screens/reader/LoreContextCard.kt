package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont

/**
 * Đoạn văn người đọc vừa chạm giữ, hiện ở dạng CHỈ ĐỌC nhưng bôi đen được: bôi đen một cái tên là điền thẳng vào ô tra cứu.
 * (Màn đọc không có thanh công cụ chọn chữ riêng; ô chữ chỉ-đọc cho ta biết đúng vùng đã bôi đen.) Chữ trong khung là chữ của
 * truyện nên dùng phông serif của phần đọc.
 */
@Composable
internal fun LoreContextCard(
    context: String,
    onTermSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    var value by remember(context) { mutableStateOf(TextFieldValue(context)) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Đoạn văn: chạm giữ rồi kéo để bôi đen tên cần tra.",
            style = AppText.captionRegular,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp)
                .clip(InputShape)
                .background(colors.card)
                .border(1.dp, colors.hairline, InputShape)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = { next ->
                    // Chỉ đọc: giữ nguyên chữ, chỉ nhận thay đổi vùng chọn
                    value = next.copy(text = context)
                    val range = next.selection
                    if (!range.collapsed && range.max <= context.length) {
                        onTermSelected(context.substring(range.min, range.max).trim())
                    }
                },
                readOnly = true,
                textStyle = AppText.body.copy(color = colors.textPrimary, fontFamily = ReaderFont),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
