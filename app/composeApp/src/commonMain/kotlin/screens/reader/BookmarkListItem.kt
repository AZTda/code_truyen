package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.shared.data.Bookmark

/**
 * Một dấu trang: thanh màu đánh dấu bên trái, đoạn #N, trích đoạn văn (serif, vì là chữ trong truyện), ghi chú (nếu có). Bấm để nhảy
 * tới; nút thùng rác để xoá (có hoàn tác). Chữ dùng màu của chủ đề đọc để đủ tương phản cả khi màu đánh dấu là vàng trên nền giấy.
 */
@Composable
internal fun BookmarkListItem(
    bookmark: Bookmark,
    onJump: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val bmColor = parseHexColor(bookmark.colorHex)

    FlatCard(
        modifier = modifier.fillMaxWidth(),
        outlined = true,
        contentPadding = PaddingValues(0.dp),
        onClick = onJump
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Spacer(modifier = Modifier.width(4.dp).fillMaxHeight().background(bmColor))
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp)) {
                Text(
                    text = "Đoạn #${bookmark.paragraphIndex + 1}",
                    style = AppText.caption,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = bookmark.selectedText,
                    style = AppText.body.copy(fontFamily = ReaderFont),
                    color = colors.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (bookmark.note.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = TruyenCloudIcons.Edit,
                            contentDescription = "Ghi chú",
                            tint = colors.textTertiary,
                            modifier = Modifier.padding(top = 2.dp).size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = bookmark.note, style = AppText.secondary, color = colors.textSecondary)
                    }
                }
            }
            QuietIconButton(
                icon = TruyenCloudIcons.Delete,
                contentDescription = "Xóa dấu trang",
                onClick = onDelete
            )
        }
    }
}
