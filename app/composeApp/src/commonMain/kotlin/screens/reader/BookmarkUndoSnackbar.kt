package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Thanh "Đã xoá dấu trang — Hoàn tác": thẻ cardHigh đặc + đường kẻ mảnh, màu theo chủ đề đọc (không còn ép nền tối).
 * Tự biến mất sau vài giây do [ReaderBookmarkCoordinator].
 */
@Composable
internal fun BookmarkUndoSnackbar(
    message: String,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(InputShape)
            .background(colors.cardHigh)
            .border(1.dp, colors.hairline, InputShape)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = AppText.secondary,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
        )
        TextActionButton(text = "Hoàn tác", onClick = onUndo)
    }
}
