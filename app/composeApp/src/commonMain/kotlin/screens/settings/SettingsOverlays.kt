package com.truyencloud.compose.screens.settings

import androidx.compose.runtime.Composable
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.NovelRepository

/**
 * Mọi hộp thoại và tờ trượt mở từ màn Cài đặt, gom một chỗ để SettingsScreen chỉ còn lo bố cục chính.
 * Tờ trượt (ModalBottomSheet) và hộp thoại tự mở cửa sổ riêng nên không cần khung bao quanh.
 *
 * Khi Cài đặt mở từ tab của HomeScreen, HomeScreen không truyền kho dữ liệu xuống nên các tham số bên dưới là null;
 * khi đó lấy từ [SettingsViewModel] — trước đây "Quản lý bộ nhớ offline" và "Xuất từ vựng" im lặng không làm gì
 * trên đường dẫn thực tế này.
 */
@Composable
fun SettingsOverlays(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    onLogout: () -> Unit,
    savedWordDao: SavedWordDao? = null,
    chapterRepository: ChapterRepository? = null,
    novelRepository: NovelRepository? = null
) {
    ModelPickerSheet(
        isOpen = uiState.isModelPickerOpen,
        models = uiState.models,
        activeModelId = uiState.activeModel,
        canSelect = uiState.profile.isAdmin,
        onSelectModel = { modelId -> viewModel.selectModel(modelId) },
        onDismiss = viewModel::dismissModelPicker
    )

    EditProfileSheet(
        isOpen = uiState.isEditProfileOpen,
        displayName = uiState.editDisplayName,
        bio = uiState.editBio,
        avatarUrl = uiState.editAvatarUrl,
        onDisplayNameChange = viewModel::onEditDisplayNameChange,
        onBioChange = viewModel::onEditBioChange,
        onAvatarUrlChange = viewModel::onEditAvatarUrlChange,
        onSave = viewModel::saveProfile,
        onDismiss = viewModel::dismissEditProfile
    )

    ExportVocabularySheet(
        isOpen = uiState.isExportSheetOpen,
        savedWordDao = savedWordDao ?: viewModel.savedWordDao,
        onDismiss = viewModel::closeExportSheet
    )

    StorageManagerSheet(
        isOpen = uiState.isStorageManagerOpen,
        chapterRepository = chapterRepository ?: viewModel.chapterRepositoryForSheets,
        novelRepository = novelRepository ?: viewModel.novelRepository,
        cleaner = viewModel.storageCleaner,
        onDismiss = viewModel::closeStorageManager
    )

    // Xác nhận xoá chương đã tải (hàng "Chương đã tải về" ở mục Đồng bộ và dữ liệu)
    ClearStorageConfirmDialog(
        confirm = uiState.storageConfirm,
        onConfirm = viewModel::confirmClearCache,
        onDismiss = viewModel::dismissClearCache
    )

    // Đăng xuất: giữ hay xoá dữ liệu trên máy
    LogoutChoiceDialog(
        isOpen = uiState.isLogoutDialogOpen,
        onKeepData = { viewModel.confirmLogout(wipeLocalData = false, onComplete = onLogout) },
        onWipeData = { viewModel.confirmLogout(wipeLocalData = true, onComplete = onLogout) },
        onDismiss = viewModel::dismissLogout
    )

    ChangePasswordDialog(
        state = uiState.changePassword,
        onOldChange = viewModel::onOldPasswordChange,
        onNewChange = viewModel::onNewPasswordChange,
        onConfirmChange = viewModel::onConfirmPasswordChange,
        onSubmit = viewModel::submitChangePassword,
        onDismiss = viewModel::closeChangePassword
    )

    // Cập nhật tại chỗ (chỉ Android: trên iOS hộp thoại này không bao giờ mở)
    AppUpdateDialog(
        isOpen = uiState.isAppUpdateDialogOpen && uiState.otaSupported,
        updateInfo = uiState.appUpdateInfo,
        currentVersionName = uiState.currentAppVersionName,
        currentVersionCode = uiState.currentAppVersionCode,
        isDownloading = uiState.isDownloadingApk,
        downloadProgress = uiState.downloadApkProgress,
        errorMessage = uiState.downloadApkError,
        onStartUpdate = viewModel::startAppUpdateDownload,
        onDismiss = viewModel::dismissAppUpdateDialog,
        isMandatory = uiState.isUpdateMandatory
    )
}
