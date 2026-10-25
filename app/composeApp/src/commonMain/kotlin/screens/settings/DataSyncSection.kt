package com.truyencloud.compose.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.LocalCustomColors

/** Các hành động của nhóm "Đồng bộ và dữ liệu" — gom lại để chữ ký hàm không phình ra nhiều tham số. */
class DataSyncActions(
    val onRequestClearCache: () -> Unit,
    val onOpenStorageManager: () -> Unit,
    val onSyncNow: (() -> Unit)?,
    val onExportVocabulary: () -> Unit,
    val onLogin: () -> Unit
)

/**
 * Nhóm "Đồng bộ và dữ liệu": đồng bộ đám mây, chương đã tải về (xoá), quản lý bộ nhớ offline và xuất từ vựng.
 * Xoá chương luôn qua hộp thoại xác nhận (xem ClearStorageConfirmDialog).
 */
@Composable
fun DataSyncSection(
    state: SettingsUiState,
    actions: DataSyncActions,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current

    InsetGroup(modifier = modifier, header = "Đồng bộ và dữ liệu") {
        CloudSyncRow(
            isLoggedIn = state.profile.isLoggedIn,
            syncState = state.syncState,
            isSyncing = state.isSyncing,
            onSyncNow = actions.onSyncNow,
            onLogin = actions.onLogin
        )

        // Xoá nội dung chương đã tải — nhãn nói đúng những gì sẽ mất, và có hộp thoại xác nhận
        ActionRow(
            title = "Chương đã tải về",
            subtitle = when {
                state.isClearingStorage -> "Đang xoá và thu gọn dữ liệu..."
                state.cacheMessage.isNotBlank() -> state.cacheMessage
                else -> "Bản gốc và mọi bản dịch (kể cả bản dịch AI): ${state.cacheSize} · ${state.cacheChapterCount} chương"
            },
            icon = TruyenCloudIcons.CleaningServices,
            iconTint = if (state.isCacheCleared) InsetIconTint.Success else InsetIconTint.Neutral,
            actionLabel = if (state.isCacheCleared) "Đã xoá" else "Xoá…",
            actionColor = if (state.isCacheCleared) colors.success else colors.error,
            isBusy = state.isClearingStorage,
            onClick = if (state.isClearingStorage) null else actions.onRequestClearCache
        )

        InsetRow(
            title = "Quản lý bộ nhớ offline",
            subtitle = "Xem dung lượng và xoá từng truyện đã tải",
            icon = TruyenCloudIcons.Download,
            iconTint = InsetIconTint.Neutral,
            showChevron = true,
            onClick = actions.onOpenStorageManager
        )

        InsetRow(
            title = "Xuất từ vựng",
            subtitle = "CSV, Anki, JSON, hoặc Markdown",
            icon = TruyenCloudIcons.Export,
            iconTint = InsetIconTint.Neutral,
            showChevron = true,
            onClick = actions.onExportVocabulary
        )
    }
}
