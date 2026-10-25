package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.IosAlertAction
import com.truyencloud.compose.components.IosAlertDialog
import com.truyencloud.compose.components.IosAlertRole
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Đăng xuất: hỏi GIỮ hay XOÁ dữ liệu đọc/từ vựng trên máy. Khách và tài khoản sau vẫn có thể dùng dữ liệu còn lại;
 * nếu sau này một tài khoản KHÁC đăng nhập trên máy này, dữ liệu còn lại sẽ bị xoá trước lần đồng bộ đầu tiên.
 */
@Composable
fun LogoutChoiceDialog(
    isOpen: Boolean,
    onKeepData: () -> Unit,
    onWipeData: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    IosAlertDialog(
        onDismissRequest = onDismiss,
        title = "Đăng xuất tài khoản?",
        message = "Tủ sách, tiến độ đọc, dấu trang và từ vựng đang lưu trên máy này. Bạn muốn giữ hay xoá chúng?",
        actions = listOf(
            IosAlertAction("Giữ dữ liệu trên máy", IosAlertRole.Primary, onClick = onKeepData),
            IosAlertAction("Xóa dữ liệu trên máy", IosAlertRole.Destructive, onClick = onWipeData),
            IosAlertAction("Hủy", IosAlertRole.Default, onClick = onDismiss)
        )
    ) {
        ChoiceNote(
            choice = "Giữ dữ liệu trên máy",
            description = "Bạn vẫn đọc tiếp như khách. Dữ liệu sẽ bị xoá nếu tài khoản khác đăng nhập trên máy này."
        )
        Spacer(modifier = Modifier.height(12.dp))
        ChoiceNote(
            choice = "Xóa dữ liệu trên máy",
            description = "Xoá sạch dữ liệu của tài khoản này khỏi máy. Dữ liệu đã đồng bộ vẫn còn trên máy chủ."
        )
    }
}

/** Giải thích hệ quả của một lựa chọn ngay trong hộp thoại, vì nút iOS chỉ đủ chỗ cho một nhãn ngắn. */
@Composable
private fun ChoiceNote(choice: String, description: String) {
    val colors = LocalCustomColors.current
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = choice, style = AppText.secondaryStrong, color = colors.textPrimary, textAlign = TextAlign.Center)
        Text(text = description, style = AppText.captionRegular, color = colors.textSecondary, textAlign = TextAlign.Center)
    }
}

/**
 * Xác nhận trước khi xoá nội dung chương đã tải. Nói thẳng những gì sẽ mất: bản dịch AI tốn thời gian sinh ra
 * sẽ phải dịch lại — bản cũ của nút này xoá hết mà không hỏi và còn ghi nhãn "chỉ ảnh bìa và bản dịch tạm".
 */
@Composable
fun ClearStorageConfirmDialog(
    confirm: StorageConfirm?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (confirm == null) return
    val colors = LocalCustomColors.current
    val target = confirm.novelTitle?.let { "\"$it\"" } ?: "tất cả truyện"

    IosAlertDialog(
        onDismissRequest = onDismiss,
        title = "Xoá chương đã tải của $target?",
        actions = listOf(
            IosAlertAction("Xoá ngay", IosAlertRole.Destructive, onClick = onConfirm),
            IosAlertAction("Hủy", IosAlertRole.Default, onClick = onDismiss)
        )
    ) {
        Text(
            text = "${confirm.chapterCount} chương · khoảng ${formatStorageBytes(confirm.bytes)}",
            style = AppText.secondaryStrong,
            color = colors.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Sẽ xoá nội dung gốc và TẤT CẢ bản dịch đã lưu (Việt, Anh, song ngữ), kể cả bản dịch AI.\n" +
                "Bản dịch AI sẽ phải dịch lại khi bạn đọc lần sau (tốn thời gian).\n\n" +
                "Mục lục, tiến độ đọc, dấu trang và từ vựng được giữ nguyên.",
            style = AppText.secondary,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}
