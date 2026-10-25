package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.IosAlertAction
import com.truyencloud.compose.components.IosAlertDialog
import com.truyencloud.compose.components.IosAlertRole
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.repo.PasswordPolicy

/** Đổi mật khẩu: mật khẩu hiện tại + mới + nhập lại (tối thiểu 8 ký tự), gọi POST /v1/auth/change-password. */
@Composable
fun ChangePasswordDialog(
    state: ChangePasswordState,
    onOldChange: (String) -> Unit,
    onNewChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!state.isOpen) return

    if (state.succeeded) {
        IosAlertDialog(
            onDismissRequest = onDismiss,
            title = "Đổi mật khẩu",
            message = "Đã đổi mật khẩu thành công. Các thiết bị khác vẫn giữ phiên đăng nhập hiện tại.",
            actions = listOf(IosAlertAction("Xong", IosAlertRole.Primary, onClick = onDismiss))
        )
        return
    }

    val colors = LocalCustomColors.current
    IosAlertDialog(
        onDismissRequest = { if (!state.isSubmitting) onDismiss() },
        title = "Đổi mật khẩu",
        dismissOnOutsideTap = !state.isSubmitting,
        actions = listOf(
            IosAlertAction(
                label = if (state.isSubmitting) "Đang đổi mật khẩu..." else "Đổi mật khẩu",
                role = IosAlertRole.Primary,
                enabled = !state.isSubmitting,
                onClick = onSubmit
            ),
            IosAlertAction("Hủy", IosAlertRole.Default, onClick = onDismiss)
        )
    ) {
        SettingsTextField(
            label = "Mật khẩu hiện tại",
            value = state.oldPassword,
            onValueChange = onOldChange,
            isPassword = true,
            enabled = !state.isSubmitting
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingsTextField(
            label = "Mật khẩu mới (tối thiểu ${PasswordPolicy.MIN_LENGTH} ký tự)",
            value = state.newPassword,
            onValueChange = onNewChange,
            isPassword = true,
            enabled = !state.isSubmitting
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingsTextField(
            label = "Nhập lại mật khẩu mới",
            value = state.confirmPassword,
            onValueChange = onConfirmChange,
            isPassword = true,
            imeAction = ImeAction.Done,
            onImeAction = onSubmit,
            enabled = !state.isSubmitting
        )
        state.error?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = AppText.secondary,
                color = colors.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
