package com.truyencloud.compose.screens.settings

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/** Tóm tắt 1 truyện đã cache — dùng cho UI StorageManager. */
data class CachedNovelInfo(
    val novelId: String,
    val title: String,
    val cachedChapters: Int,
    val sizeBytes: Long
)

/**
 * Một truyện đã tải về máy: tên (chữ serif vì là tên sách), số chương + dung lượng, chữ "Xoá" màu lỗi bên phải.
 * Chạm cả dòng để mở hộp thoại xác nhận xoá.
 */
@Composable
fun CachedNovelRow(
    title: String,
    cachedChapters: Int,
    sizeBytes: Long,
    isClearing: Boolean,
    onClear: () -> Unit
) {
    val colors = LocalCustomColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = !isClearing,
                    onClickLabel = "Xoá dữ liệu đã tải của truyện này",
                    role = Role.Button,
                    onClick = onClear
                )
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppText.bookTitleCompact,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$cachedChapters chương · ${formatStorageBytes(sizeBytes)}",
                    style = AppText.secondary,
                    color = colors.textSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (isClearing) RowSpinner(colors.error) else RowActionText("Xoá", colors.error)
        }
        HairlineDivider(startInset = 20.dp)
    }
}

fun formatStorageBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> {
            val mb = (bytes * 10 / (1024 * 1024)) / 10.0
            "$mb MB"
        }
        else -> {
            val gb = (bytes * 100 / (1024 * 1024 * 1024)) / 100.0
            "$gb GB"
        }
    }
}
