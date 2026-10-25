package com.truyencloud.compose.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.icons.TruyenCloudIcons

/**
 * Nhóm "Tài khoản": đổi mật khẩu và đăng xuất. Chỉ hiện khi đã đăng nhập; khách dùng nút "Đăng nhập" ở hàng hồ sơ
 * (UserProfileHeader), nên không có nhóm rỗng.
 */
@Composable
fun AccountSection(
    isLoggedIn: Boolean,
    onChangePassword: () -> Unit,
    onRequestLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isLoggedIn) return
    InsetGroup(modifier = modifier, header = "Tài khoản") {
        InsetRow(
            title = "Đổi mật khẩu",
            icon = TruyenCloudIcons.Lock,
            showChevron = true,
            onClick = onChangePassword
        )
        InsetRow(
            title = "Đăng xuất",
            icon = TruyenCloudIcons.Logout,
            iconTint = InsetIconTint.Error,
            destructive = true,
            onClick = onRequestLogout
        )
    }
}
