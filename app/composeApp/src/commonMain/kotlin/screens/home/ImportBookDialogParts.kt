package com.truyencloud.compose.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.platform.PickedBookFile
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/** Dữ liệu đang gõ trong hộp thoại nạp tệp. Có tệp thì nội dung văn bản bị bỏ, gõ nội dung thì bỏ tệp đã chọn. */
internal class ImportFormState(title: String = "", author: String = "", content: String = "", pickedFile: PickedBookFile? = null) {
    var title by mutableStateOf(title)
    var author by mutableStateOf(author)
    var content by mutableStateOf(content)
    var pickedFile by mutableStateOf(pickedFile)

    /** Đủ tên và có nội dung (văn bản hoặc tệp) thì mới nạp được. */
    val canSubmit: Boolean get() = title.isNotBlank() && (content.isNotBlank() || pickedFile != null)

    fun pick(file: PickedBookFile) {
        pickedFile = file
        if (title.isBlank()) title = file.name.substringBeforeLast('.', file.name)
        content = ""
    }

    fun edit(text: String) {
        content = text
        if (text.isNotBlank()) pickedFile = null
    }

    fun fillSample() {
        pickedFile = null
        title = "Bạch Dạ Kỳ Hiệp"
        author = "Cố Tử Lam"
        content = DEFAULT_SAMPLE_TEXT
    }
}

/** Các ô của hộp thoại: chọn tệp, tên, tác giả, ô dán nội dung, văn bản mẫu và lỗi (nếu có). */
@Composable
internal fun ImportFormFields(form: ImportFormState, errorMessage: String?, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        ImportFilePickerRow(selectedFileName = form.pickedFile?.name, onFilePicked = form::pick)

        ImportField(label = "Tên tác phẩm", value = form.title, onValueChange = { form.title = it })
        ImportField(label = "Tác giả", value = form.author, onValueChange = { form.author = it })
        ImportField(
            label = if (form.pickedFile == null) "Nội dung văn bản (tự tách chương)" else "Nội dung đọc từ tệp",
            value = form.content,
            onValueChange = form::edit,
            singleLine = false,
            readerText = true,
            sideLabel = "${form.content.length} ký tự"
        )

        TextActionButton(text = "Dùng văn bản mẫu", onClick = form::fillSample, modifier = Modifier.fillMaxWidth())

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = AppText.secondary,
                color = colors.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Ô nhập của hộp thoại nạp tệp: nhãn 13sp Medium phía trên, ô bo 12 viền outline (đổi sang màu nhấn khi đang gõ).
 * [readerText] dùng chữ serif cho ô dán nội dung truyện; [sideLabel] là chữ nhỏ bên phải nhãn (số ký tự).
 */
@Composable
internal fun ImportField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    readerText: Boolean = false,
    sideLabel: String? = null
) {
    val colors = LocalCustomColors.current
    var focused by remember { mutableStateOf(false) }
    val textStyle = if (readerText) {
        AppText.bookTitle.copy(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal)
    } else {
        AppText.body
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = AppText.secondaryStrong, color = colors.textSecondary, modifier = Modifier.weight(1f))
            if (sideLabel != null) {
                Text(text = sideLabel, style = AppText.captionRegular, color = colors.textTertiary)
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = textStyle.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                // Ô dán văn bản cao 120-180dp rồi tự cuộn bên trong: truyện dán vào có thể dài hàng nghìn dòng
                .then(if (singleLine) Modifier.heightIn(min = 48.dp) else Modifier.heightIn(min = 120.dp, max = 180.dp))
                .onFocusChanged { focused = it.hasFocus }
                .clip(InputShape)
                .background(colors.card)
                .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.outline, InputShape)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        )
    }
}
