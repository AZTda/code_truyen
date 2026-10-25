package com.truyencloud.compose.screens.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetTextFieldRow
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Biểu mẫu đăng nhập: hai ô trong một nhóm inset (tên tài khoản / email, mật khẩu có nút hiện-ẩn), thông báo lỗi có biểu tượng,
 * nút chính "Đăng nhập" và nút chữ "Tiếp tục với tư cách khách". Tự co theo cỡ chữ lớn: không có chiều cao cố định.
 */
@Composable
fun SignInForm(
    uiState: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onContinueAsGuest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val focusManager = LocalFocusManager.current
    var isPasswordVisible by remember { mutableStateOf(false) }
    // Gửi xong thì hạ bàn phím để thông báo lỗi / vòng chờ không bị che
    val submit = {
        focusManager.clearFocus()
        onSubmit()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        InsetGroup {
            InsetTextFieldRow(
                value = uiState.email,
                onValueChange = onEmailChange,
                placeholder = "Tên tài khoản hoặc email",
                icon = TruyenCloudIcons.Person,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next)
            )
            InsetTextFieldRow(
                value = uiState.password,
                onValueChange = onPasswordChange,
                placeholder = "Mật khẩu",
                icon = TruyenCloudIcons.Lock,
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailing = {
                    QuietIconButton(
                        icon = if (isPasswordVisible) TruyenCloudIcons.Visibility else TruyenCloudIcons.VisibilityOff,
                        contentDescription = if (isPasswordVisible) "Ẩn mật khẩu" else "Hiện mật khẩu",
                        onClick = { isPasswordVisible = !isPasswordVisible },
                        // Nút hiện/ẩn không được chen vào thứ tự tab: nếu không, nút "Tiếp" của bàn phím nhảy vào biểu tượng con mắt
                        modifier = Modifier.focusProperties { canFocus = false },
                        iconSize = 20.dp
                    )
                }
            )
        }

        if (uiState.errorMessage != null) {
            NoticeBanner(
                text = uiState.errorMessage,
                tone = NoticeTone.Error,
                modifier = Modifier.padding(start = Spacing.groupInset, end = Spacing.groupInset, top = Spacing.md)
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        PrimaryButton(
            text = "Đăng nhập",
            onClick = submit,
            isLoading = uiState.isLoading,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.groupInset)
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        TextActionButton(
            text = "Tiếp tục với tư cách khách",
            onClick = onContinueAsGuest,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        Text(
            text = "Hệ thống riêng tư nội bộ. Đăng ký tài khoản do quản trị viên cấp.",
            style = AppText.captionRegular,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl)
        )
    }
}
