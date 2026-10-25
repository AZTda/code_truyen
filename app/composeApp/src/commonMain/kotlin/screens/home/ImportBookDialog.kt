package com.truyencloud.compose.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.truyencloud.compose.components.IosAlertAction
import com.truyencloud.compose.components.IosAlertDialog
import com.truyencloud.compose.components.IosAlertRole
import com.truyencloud.compose.platform.PickedBookFile

/**
 * Hộp thoại nạp tệp sách cá nhân (TXT / EPUB hoặc dán văn bản) kiểu iOS: tiêu đề + mô tả ở giữa, biểu mẫu, rồi các nút xếp
 * dọc ("Nạp vào tủ sách", "Hủy"). Chạm ra ngoài không đóng để không mất chữ đã nhập; nút "Hủy" hoặc phím Back mới đóng.
 */
@Composable
fun ImportBookDialog(
    isImporting: Boolean = false,
    onImport: (title: String, author: String, content: String) -> Unit,
    onImportFile: (title: String, author: String, file: PickedBookFile) -> Unit = { _, _, _ -> },
    onDismiss: () -> Unit,
    /** Lỗi của lần nạp vừa rồi (thân thiện) — hiện ngay trong hộp thoại thay vì ở dải phía sau. */
    errorMessage: String? = null
) {
    val form = remember { ImportFormState() }

    IosAlertDialog(
        onDismissRequest = onDismiss,
        title = IMPORT_DIALOG_TITLE,
        message = IMPORT_DIALOG_MESSAGE,
        dismissOnOutsideTap = false,
        actions = importDialogActions(form, isImporting, onImport, onImportFile, onDismiss)
    ) {
        ImportFormFields(form = form, errorMessage = errorMessage)
    }
}

internal const val IMPORT_DIALOG_TITLE = "Nạp tệp sách"
internal const val IMPORT_DIALOG_MESSAGE = "Hỗ trợ tệp .txt hoặc .epub, hoặc dán văn bản để ứng dụng tự tách chương."

/** Nút của hộp thoại: nạp (chỉ bấm được khi đủ tên + nội dung) và Hủy. */
internal fun importDialogActions(
    form: ImportFormState,
    isImporting: Boolean,
    onImport: (title: String, author: String, content: String) -> Unit,
    onImportFile: (title: String, author: String, file: PickedBookFile) -> Unit,
    onDismiss: () -> Unit
): List<IosAlertAction> = listOf(
    IosAlertAction(
        label = if (isImporting) "Đang nạp…" else "Nạp vào tủ sách",
        role = IosAlertRole.Primary,
        enabled = form.canSubmit && !isImporting
    ) {
        val file = form.pickedFile
        if (file != null) onImportFile(form.title.trim(), form.author.trim(), file)
        else onImport(form.title.trim(), form.author.trim(), form.content.trim())
    },
    IosAlertAction(label = "Hủy", role = IosAlertRole.Default, onClick = onDismiss)
)
