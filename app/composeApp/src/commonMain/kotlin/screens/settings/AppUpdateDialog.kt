package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.IosAlertAction
import com.truyencloud.compose.components.IosAlertDialog
import com.truyencloud.compose.components.IosAlertRole
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.AppVersionResponse

/**
 * "Có bản cập nhật mới" (chỉ Android). Bản bắt buộc không có nút thoát cho tới khi tải hỏng / sai mã kiểm tra, để
 * hộp thoại không khoá cứng cả ứng dụng.
 */
@Composable
fun AppUpdateDialog(
    isOpen: Boolean,
    updateInfo: AppVersionResponse?,
    currentVersionName: String,
    currentVersionCode: Int,
    isDownloading: Boolean,
    downloadProgress: Float,
    errorMessage: String?,
    onStartUpdate: () -> Unit,
    onDismiss: () -> Unit,
    /** Bản bắt buộc (máy chủ đặt is_mandatory hoặc bản đang chạy thấp hơn min_version_code). */
    isMandatory: Boolean = updateInfo?.isMandatory == true
) {
    if (!isOpen || updateInfo == null) return

    val colors = LocalCustomColors.current
    // Bản bắt buộc vẫn phải có lối thoát khi tải hỏng / sai mã kiểm tra, nếu không hộp thoại khoá cứng cả ứng dụng
    val canDismiss = !isDownloading && (!isMandatory || !errorMessage.isNullOrBlank())

    IosAlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = "Bản cập nhật mới",
        message = "Phiên bản v${updateInfo.versionName} (Build ${updateInfo.versionCode})",
        dismissOnOutsideTap = canDismiss,
        actions = buildList {
            add(
                IosAlertAction(
                    label = if (isDownloading) "Đang tải..." else "Cập nhật ngay",
                    role = IosAlertRole.Primary,
                    enabled = !isDownloading,
                    onClick = onStartUpdate
                )
            )
            if (canDismiss) {
                add(IosAlertAction(if (isMandatory) "Đóng" else "Để sau", IosAlertRole.Default, onClick = onDismiss))
            }
        }
    ) {
        UpdateVersionAndChangelog(
            updateInfo = updateInfo,
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode
        )

        if (isDownloading) {
            UpdateDownloadProgress(downloadProgress)
        }

        if (!errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMessage,
                style = AppText.secondaryStrong,
                color = colors.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
