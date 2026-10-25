package com.truyencloud.compose.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.repo.SyncStatus
import com.truyencloud.shared.repo.SyncStatusText
import com.truyencloud.shared.repo.SyncTone
import kotlinx.coroutines.delay

/**
 * Hàng "Đồng bộ đám mây" phản ánh trạng thái THẬT từ [SyncStatus]: lần đồng bộ thành công gần nhất, lỗi gần nhất,
 * "Chưa đăng nhập" cho khách, "Phiên hết hạn — đăng nhập lại" khi token bị từ chối. Ô biểu tượng đổi màu theo trạng thái
 * (xanh / vàng / đỏ) kèm biểu tượng khác nhau, nên không chỉ dựa vào màu.
 */
@Composable
fun CloudSyncRow(
    isLoggedIn: Boolean,
    syncState: SyncStatus.State,
    isSyncing: Boolean,
    onSyncNow: (() -> Unit)?,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    // "5 phút trước" phải tự tươi lại khi màn hình mở lâu
    val now by produceState(initialValue = getCurrentEpochMillis()) {
        while (true) {
            delay(30_000)
            value = getCurrentEpochMillis()
        }
    }
    val line = SyncStatusText.describe(isLoggedIn, syncState, now)

    val tint = when (line.tone) {
        SyncTone.OK -> InsetIconTint.Success
        SyncTone.WARNING -> InsetIconTint.Warning
        SyncTone.ERROR -> InsetIconTint.Error
        SyncTone.NEUTRAL -> InsetIconTint.Neutral
    }
    val icon = when (line.tone) {
        SyncTone.OK -> TruyenCloudIcons.CloudDone
        SyncTone.WARNING, SyncTone.ERROR -> TruyenCloudIcons.CloudOff
        SyncTone.NEUTRAL -> TruyenCloudIcons.CloudSync
    }

    val needsLogin = !isLoggedIn || syncState.sessionExpired
    ActionRow(
        title = "Đồng bộ đám mây",
        subtitle = "${line.title} · ${line.detail}",
        icon = icon,
        modifier = modifier,
        iconTint = tint,
        actionLabel = when {
            needsLogin -> "Đăng nhập"
            onSyncNow != null -> "Đồng bộ ngay"
            else -> null
        },
        isBusy = isSyncing && !needsLogin,
        stackLongText = true,
        onClick = when {
            needsLogin -> onLogin
            isSyncing -> null
            else -> onSyncNow
        }
    )
}
