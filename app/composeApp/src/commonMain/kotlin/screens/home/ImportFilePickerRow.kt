package com.truyencloud.compose.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.InsetIconTile
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.platform.PickedBookFile
import com.truyencloud.compose.platform.rememberBookFilePicker
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors

/** Hàng chọn tệp TXT / EPUB trong hộp thoại nạp tệp: biểu tượng tải lên, tên tệp đã chọn (hoặc lời mời chọn) và gợi ý định dạng. */
@Composable
fun ImportFilePickerRow(
    selectedFileName: String?,
    onFilePicked: (PickedBookFile) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val openPicker = rememberBookFilePicker(onFilePicked)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(InputShape)
            .background(colors.card)
            .border(1.dp, colors.outline, InputShape)
            .clickable(role = Role.Button, onClick = openPicker)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InsetIconTile(icon = TruyenCloudIcons.FileUpload, tint = InsetIconTint.Accent)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = selectedFileName ?: "Chọn tệp sách",
                style = AppText.body,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (selectedFileName == null) "TXT hoặc EPUB" else "Chạm để chọn tệp khác",
                style = AppText.secondary,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
