package com.truyencloud.compose.screens.settings

import androidx.compose.runtime.Composable
import com.truyencloud.compose.components.IosAlertAction
import com.truyencloud.compose.components.IosAlertDialog
import com.truyencloud.compose.components.IosAlertRole
import com.truyencloud.shared.repo.AccountNotices

/**
 * Báo rõ khi đăng xuất / đổi tài khoản buộc phải bỏ lại thay đổi chưa kịp đồng bộ (mất mạng, máy chủ bận). Việc đăng
 * xuất vẫn xảy ra — hộp thoại chỉ để người dùng không bị mất dữ liệu trong im lặng.
 */
@Composable
fun AccountNoticeDialog(message: String, onDismiss: () -> Unit) {
    IosAlertDialog(
        onDismissRequest = onDismiss,
        title = AccountNotices.TITLE,
        message = message,
        actions = listOf(IosAlertAction("Đã hiểu", IosAlertRole.Primary, onClick = onDismiss))
    )
}
