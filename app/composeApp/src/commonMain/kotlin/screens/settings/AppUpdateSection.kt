package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Nhóm "Cập nhật ứng dụng" (chỉ Android — iOS không cài được gói ngoài kho nên chỉ hiện số phiên bản ở cuối trang).
 * Có bản mới thì hàng đổi thành "Có bản cập nhật mới" kèm chữ "Nâng cấp"; chạm vào mở hộp thoại cập nhật.
 */
@Composable
fun AppUpdateSection(
    currentAppVersionName: String,
    currentAppVersionCode: Int,
    isCheckingAppUpdate: Boolean,
    hasAppUpdate: Boolean,
    appUpdateStatusMessage: String?,
    onCheckAppUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val status = if (isCheckingAppUpdate) "Đang kiểm tra từ máy chủ..."
    else appUpdateStatusMessage ?: "Phiên bản v$currentAppVersionName (Build $currentAppVersionCode)"

    InsetGroup(modifier = modifier, header = "Cập nhật ứng dụng") {
        ActionRow(
            title = if (hasAppUpdate) "Có bản cập nhật mới" else "Kiểm tra cập nhật",
            subtitle = status,
            icon = TruyenCloudIcons.Download,
            iconTint = if (hasAppUpdate) InsetIconTint.Success else InsetIconTint.Accent,
            actionLabel = if (hasAppUpdate) "Nâng cấp" else "Kiểm tra",
            actionColor = colors.accent,
            isBusy = isCheckingAppUpdate,
            onClick = if (isCheckingAppUpdate) null else onCheckAppUpdate
        )
    }
}

/** Dòng phiên bản ở cuối trang Cài đặt (cả Android và iOS). */
@Composable
fun VersionFooter(versionName: String, versionCode: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "TruyenCloud · Phiên bản $versionName (Build $versionCode)",
            style = AppText.caption,
            color = LocalCustomColors.current.textTertiary,
            textAlign = TextAlign.Center
        )
    }
}
