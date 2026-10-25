package com.truyencloud.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.library.LibraryFormat

/**
 * Xác nhận gỡ một hoặc nhiều truyện khỏi tủ sách (hộp thoại kiểu iOS). Hiện dung lượng dữ liệu đọc offline trên máy và
 * cho chọn xóa luôn (mặc định GIỮ: tải lại chương tốn mạng và có thể tốn cả lượt dịch AI).
 *
 * @param titles tên hiển thị của các truyện sẽ gỡ.
 * @param offlineBytes tổng dung lượng chương đã tải của các truyện đó.
 */
@Composable
fun LibraryRemoveDialog(
    titles: List<String>,
    offlineBytes: Long,
    onConfirm: (deleteOfflineData: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalCustomColors.current
    var deleteOffline by remember { mutableStateOf(false) }
    val hasOffline = offlineBytes > 0L

    IosAlertDialog(
        onDismissRequest = onDismiss,
        title = if (titles.size > 1) "Xóa ${titles.size} truyện khỏi tủ sách?" else "Xóa khỏi tủ sách?",
        message = describe(titles),
        actions = listOf(
            IosAlertAction("Xóa khỏi tủ", IosAlertRole.Destructive) { onConfirm(deleteOffline) },
            IosAlertAction("Giữ lại", IosAlertRole.Default, onClick = onDismiss)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChipShape)
                .toggleable(value = deleteOffline, role = Role.Checkbox, onValueChange = { deleteOffline = it })
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = deleteOffline,
                onCheckedChange = null,
                modifier = Modifier.padding(end = 4.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.accent,
                    checkmarkColor = colors.onAccent,
                    uncheckedColor = colors.outline
                )
            )
            Column {
                Text(text = "Xóa cả dữ liệu đọc offline", style = AppText.body, color = colors.textPrimary)
                Text(
                    text = if (hasOffline) "Đang chiếm ${LibraryFormat.bytes(offlineBytes)} trên máy" else "Chưa tải chương nào về máy",
                    style = AppText.secondary,
                    color = colors.textSecondary
                )
            }
        }
    }
}

private fun describe(titles: List<String>): String {
    val consequence = "Tiến độ đọc trên máy này sẽ bị xóa; dấu trang và ghi chú của bạn vẫn được giữ."
    return when (titles.size) {
        0 -> consequence
        1 -> "\"${titles.first()}\" sẽ biến mất khỏi Tủ sách. $consequence"
        else -> {
            val shown = titles.take(3).joinToString(", ") { "\"$it\"" }
            val more = if (titles.size > 3) " và ${titles.size - 3} truyện khác" else ""
            "$shown$more sẽ biến mất khỏi Tủ sách. $consequence"
        }
    }
}
