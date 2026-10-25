package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.IosAlertAction
import com.truyencloud.compose.components.IosAlertDialog
import com.truyencloud.compose.components.IosAlertRole
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors

/** Xác nhận gỡ truyện khỏi tủ sách (hộp thoại kiểu iOS), có tuỳ chọn xóa luôn các chương đã tải. Mặc định GIỮ chương đã tải. */
@Composable
fun RemoveFromLibraryDialog(
    novelTitle: String,
    onConfirm: (deleteOfflineData: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var deleteOffline by remember { mutableStateOf(false) }

    IosAlertDialog(
        onDismissRequest = onDismiss,
        title = "Gỡ khỏi tủ sách?",
        message = "\"$novelTitle\" sẽ biến mất khỏi Tủ sách và tiến độ đọc trên máy này sẽ bị xóa. " +
            "Dấu trang và ghi chú của bạn vẫn được giữ.",
        actions = listOf(
            IosAlertAction("Gỡ khỏi tủ", IosAlertRole.Destructive) { onConfirm(deleteOffline) },
            IosAlertAction("Giữ lại", IosAlertRole.Default, onClick = onDismiss)
        )
    ) {
        DeleteOfflineRow(checked = deleteOffline, onCheckedChange = { deleteOffline = it })
    }
}

/** Hàng chọn "Xóa luôn các chương đã tải về máy": cả hàng là vùng bấm 48dp, ô tích chỉ để hiển thị. */
@Composable
internal fun DeleteOfflineRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalCustomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ChipShape)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.padding(end = 8.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = colors.accent,
                checkmarkColor = colors.onAccent,
                uncheckedColor = colors.outline
            )
        )
        Text(text = "Xóa luôn các chương đã tải về máy", style = AppText.body, color = colors.textPrimary)
    }
}
