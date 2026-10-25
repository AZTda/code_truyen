package com.truyencloud.compose.screens.auth

import androidx.compose.runtime.Composable
import com.truyencloud.compose.components.IosConfirmDialog

/**
 * Hộp thoại đề nghị đăng nhập, có thể đóng: dùng cho "Phiên đăng nhập đã hết hạn" và cho lời mời đăng nhập
 * một lần sau hướng dẫn chào mừng ở lần chạy đầu. Nút xác nhận dẫn tới màn hình đăng nhập.
 */
@Composable
fun AuthPromptDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    IosConfirmDialog(
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        dismissLabel = dismissLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/** "Phiên đăng nhập đã hết hạn — đăng nhập lại". Dữ liệu trên máy vẫn được giữ nguyên. */
@Composable
fun SessionExpiredDialog(onLogin: () -> Unit, onDismiss: () -> Unit) {
    AuthPromptDialog(
        title = "Phiên đăng nhập đã hết hạn",
        message = "Đăng nhập lại để tiếp tục đồng bộ tủ sách và tiến độ đọc. " +
            "Dữ liệu đang có trên máy vẫn được giữ nguyên, bạn vẫn đọc bình thường dưới chế độ khách.",
        confirmLabel = "Đăng nhập lại",
        dismissLabel = "Để sau",
        onConfirm = onLogin,
        onDismiss = onDismiss
    )
}

/** Lời mời đăng nhập hiện đúng một lần, sau hướng dẫn chào mừng ở lần chạy đầu tiên. */
@Composable
fun LoginOfferDialog(onLogin: () -> Unit, onDismiss: () -> Unit) {
    AuthPromptDialog(
        title = "Đăng nhập để đồng bộ?",
        message = "Có tài khoản do quản trị viên cấp, bạn có thể đồng bộ tủ sách và tiến độ đọc giữa các thiết bị. " +
            "Hoặc cứ đọc ngay dưới chế độ khách, đăng nhập sau trong Cài đặt.",
        confirmLabel = "Đăng nhập",
        dismissLabel = "Để sau",
        onConfirm = onLogin,
        onDismiss = onDismiss
    )
}
