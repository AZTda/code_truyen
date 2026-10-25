package com.truyencloud.compose.screens.settings

import com.truyencloud.shared.api.AppUpdateApi
import com.truyencloud.shared.api.ErrorContext
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.repo.UserRepository
import com.truyencloud.shared.update.ApkDownloadRequest
import com.truyencloud.shared.update.AppUpdateInstaller
import com.truyencloud.shared.update.UpdateDecision
import com.truyencloud.shared.update.UpdatePlatform
import com.truyencloud.shared.update.UpdatePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Cập nhật ứng dụng tại chỗ (OTA) — CHỈ Android.
 *
 * iOS không cài được APK nên toàn bộ luồng bị bỏ qua ở đó: không hỏi máy chủ, không hiện hộp thoại, không có
 * nút và không bao giờ mở URL APK. Bản cũ gửi `check-update` từ iOS với versionCode cố định 1, nên mọi lần mở
 * Cài đặt đều thấy "bản mới" và hộp thoại bắt buộc không đóng được chặn luôn tab Cài đặt.
 */
class SettingsUpdateCoordinator(
    private val appUpdateApi: AppUpdateApi,
    private val userRepository: UserRepository,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val scope: CoroutineScope,
    private val platform: UpdatePlatform = AppUpdateInstaller.platform
) {
    fun checkForAppUpdates(silent: Boolean = false) {
        if (!UpdatePolicy.supportsOta(platform)) {
            uiState.value = uiState.value.copy(
                otaSupported = false,
                isCheckingAppUpdate = false,
                appUpdateInfo = null,
                isAppUpdateDialogOpen = false,
                isUpdateMandatory = false,
                appUpdateStatusMessage = null
            )
            return
        }
        scope.launch {
            uiState.value = uiState.value.copy(
                isCheckingAppUpdate = true,
                appUpdateStatusMessage = if (!silent) "Đang kiểm tra bản cập nhật..." else null
            )
            try {
                val currentCode = AppUpdateInstaller.getCurrentVersionCode()
                val currentName = AppUpdateInstaller.getCurrentVersionName()
                val info = appUpdateApi.checkUpdate(platform)
                val decision = UpdatePolicy.decide(
                    platform = platform,
                    currentVersionCode = currentCode,
                    latestVersionCode = info.versionCode,
                    serverSaysMandatory = info.isMandatory,
                    minVersionCode = info.minVersionCode
                )
                val available = decision as? UpdateDecision.Available

                uiState.value = uiState.value.copy(
                    isCheckingAppUpdate = false,
                    appUpdateInfo = info,
                    currentAppVersionCode = currentCode,
                    currentAppVersionName = currentName,
                    isAppUpdateDialogOpen = available != null,
                    isUpdateMandatory = available?.mandatory == true,
                    appUpdateStatusMessage = when {
                        available != null -> "Có bản mới: v${info.versionName} (Build ${info.versionCode})"
                        !silent -> "Bạn đang dùng phiên bản mới nhất (v$currentName)"
                        else -> null
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    isCheckingAppUpdate = false,
                    appUpdateStatusMessage = if (!silent) {
                        "Không kiểm tra được cập nhật: ${FriendlyErrors.of(e, ErrorContext.UPDATE_CHECK)}"
                    } else null
                )
            }
        }
    }

    fun openAppUpdateDialog() {
        if (!UpdatePolicy.supportsOta(platform)) return
        if (uiState.value.hasAppUpdate) {
            uiState.value = uiState.value.copy(isAppUpdateDialogOpen = true)
        } else {
            checkForAppUpdates(silent = false)
        }
    }

    fun dismissAppUpdateDialog() {
        uiState.value = uiState.value.copy(isAppUpdateDialogOpen = false)
    }

    fun startAppUpdateDownload() {
        if (!UpdatePolicy.supportsOta(platform) || uiState.value.isDownloadingApk) return
        val info = uiState.value.appUpdateInfo ?: return
        uiState.value = uiState.value.copy(
            isDownloadingApk = true,
            downloadApkProgress = 0f,
            downloadApkError = null
        )
        scope.launch {
            try {
                AppUpdateInstaller.downloadAndInstallApk(
                    request = ApkDownloadRequest(
                        baseUrl = userRepository.profile.value.serverUrl,
                        downloadUrl = info.downloadUrl,
                        expectedSha256 = info.sha256,
                        expectedSizeBytes = info.fileSizeBytes
                    ),
                    onProgress = { progress ->
                        uiState.value = uiState.value.copy(downloadApkProgress = progress)
                    },
                    onError = { err ->
                        uiState.value = uiState.value.copy(isDownloadingApk = false, downloadApkError = err)
                    }
                )
            } finally {
                // Thành công: màn hình cài đặt hệ thống đã mở, hộp thoại không còn phải giữ trạng thái "đang tải"
                if (uiState.value.isDownloadingApk && uiState.value.downloadApkError == null) {
                    uiState.value = uiState.value.copy(isDownloadingApk = false)
                }
            }
        }
    }
}
