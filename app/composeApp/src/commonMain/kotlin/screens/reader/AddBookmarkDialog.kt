package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.shared.data.Bookmark

/**
 * Tạo / sửa dấu trang của một đoạn văn (mở bằng chạm giữ đoạn văn). [onExplainTerm] khác null thì có thêm nút "Giải thích /
 * Ghim tên": mở bảng tra cứu với chính đoạn văn này làm ngữ cảnh để người đọc bôi đen một cái tên rồi ghim vào bảng thuật ngữ.
 */
@Composable
fun AddBookmarkDialog(
    snippetText: String,
    existingBookmark: Bookmark? = null,
    onSave: (colorHex: String, note: String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onExplainTerm: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AddBookmarkDialogBody(snippetText, existingBookmark, onSave, onDelete, onExplainTerm, onDismiss)
    }
}

@Composable
internal fun AddBookmarkDialogBody(
    snippetText: String,
    existingBookmark: Bookmark?,
    onSave: (colorHex: String, note: String) -> Unit,
    onDelete: (() -> Unit)?,
    onExplainTerm: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val colors = LocalCustomColors.current
    var selectedColor by remember { mutableStateOf(existingBookmark?.colorHex ?: BookmarkColors.first().first) }
    var noteText by remember { mutableStateOf(existingBookmark?.note ?: "") }

    // Hộp thoại kiểu iOS: tiêu đề cố định, thân cuộn được, nút Lưu / Xóa ghim đáy (xem ReaderDialogFrame)
    ReaderDialogFrame(
        onDismiss = onDismiss,
        maxWidth = 420.dp,
        header = {
            Icon(
                imageVector = TruyenCloudIcons.BookmarkFilled,
                contentDescription = null,
                tint = parseHexColor(selectedColor),
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (existingBookmark != null) "Sửa dấu trang" else "Tạo dấu trang",
                style = AppText.sectionHeader,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
        },
        actions = {
            BookmarkDialogActions(
                canDelete = existingBookmark != null && onDelete != null,
                onDelete = {
                    onDelete?.invoke()
                    onDismiss()
                },
                onSave = {
                    onSave(selectedColor, noteText)
                    onDismiss()
                },
                onCancel = onDismiss
            )
        }
    ) {
        FlatCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = colors.card,
            outlined = true,
            contentPadding = PaddingValues(12.dp)
        ) {
            Text(
                text = snippetText,
                style = AppText.body.copy(fontFamily = ReaderFont),
                color = colors.textPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onExplainTerm != null) {
            Spacer(modifier = Modifier.height(8.dp))
            ExplainTermButton(onClick = onExplainTerm)
        }

        Spacer(modifier = Modifier.height(16.dp))
        ReaderSectionLabel("Màu đánh dấu")
        Spacer(modifier = Modifier.height(4.dp))
        BookmarkColorPicker(selectedColor = selectedColor, onSelect = { selectedColor = it })

        Spacer(modifier = Modifier.height(12.dp))
        ReaderSectionLabel("Ghi chú (tùy chọn)")
        Spacer(modifier = Modifier.height(8.dp))
        ReaderInputField(
            value = noteText,
            onValueChange = { noteText = it },
            placeholder = "Nhập cảm nghĩ, ý nghĩa hoặc ghi chú…",
            singleLine = false,
            minHeight = 96.dp,
            maxLines = 5,
            containerColor = colors.card
        )
    }
}
