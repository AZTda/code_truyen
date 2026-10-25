package com.truyencloud.compose.screens.settings

import com.truyencloud.shared.api.ErrorContext
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.repo.PasswordPolicy
import com.truyencloud.shared.repo.SyncStatus
import com.truyencloud.shared.repo.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Đăng xuất (có lựa chọn giữ/xoá dữ liệu), đổi mật khẩu và "Đồng bộ ngay". */
class SettingsAccountCoordinator(
    private val userRepository: UserRepository,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val scope: CoroutineScope,
    /** Đẩy/kéo dữ liệu đám mây; null nếu ứng dụng không nối được (ví dụ kiểm thử). */
    private val syncNowAction: (suspend () -> Unit)?
) {
    // ── Đăng xuất ────────────────────────────────────────────────────────────────

    fun requestLogout() {
        uiState.value = uiState.value.copy(isLogoutDialogOpen = true)
    }

    fun dismissLogout() {
        uiState.value = uiState.value.copy(isLogoutDialogOpen = false)
    }

    /**
     * [wipeLocalData]: true = "Xóa dữ liệu trên máy", false = "Giữ dữ liệu trên máy".
     * Đăng xuất đẩy nốt thay đổi chưa đồng bộ trước (tối đa ~5 giây), nên hiện trạng thái "đang đồng bộ" trong lúc chờ.
     */
    fun confirmLogout(wipeLocalData: Boolean, onComplete: () -> Unit) {
        uiState.value = uiState.value.copy(isLogoutDialogOpen = false, isSyncing = true)
        scope.launch {
            try {
                userRepository.logout(wipeLocalData)
            } finally {
                uiState.value = uiState.value.copy(isSyncing = false)
            }
            onComplete()
        }
    }

    // ── Đổi mật khẩu ─────────────────────────────────────────────────────────────

    private fun updatePassword(transform: (ChangePasswordState) -> ChangePasswordState) {
        uiState.value = uiState.value.copy(changePassword = transform(uiState.value.changePassword))
    }

    fun openChangePassword() = updatePassword { ChangePasswordState(isOpen = true) }

    fun closeChangePassword() = updatePassword { ChangePasswordState() }

    fun onOldPasswordChange(value: String) = updatePassword { it.copy(oldPassword = value, error = null) }
    fun onNewPasswordChange(value: String) = updatePassword { it.copy(newPassword = value, error = null) }
    fun onConfirmPasswordChange(value: String) = updatePassword { it.copy(confirmPassword = value, error = null) }

    fun submitChangePassword() {
        val form = uiState.value.changePassword
        if (form.isSubmitting || form.succeeded) return
        PasswordPolicy.validateChange(form.oldPassword, form.newPassword, form.confirmPassword)?.let { message ->
            updatePassword { it.copy(error = message) }
            return
        }
        updatePassword { it.copy(isSubmitting = true, error = null) }
        scope.launch {
            try {
                userRepository.changePassword(form.oldPassword, form.newPassword)
                // Xoá mật khẩu khỏi bộ nhớ ngay khi xong, không giữ lại trong state
                updatePassword { ChangePasswordState(isOpen = true, succeeded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updatePassword { it.copy(isSubmitting = false, error = FriendlyErrors.of(e, ErrorContext.CHANGE_PASSWORD)) }
            }
        }
    }

    // ── Đồng bộ ngay ─────────────────────────────────────────────────────────────

    fun syncNow() {
        val action = syncNowAction ?: return
        if (uiState.value.isSyncing || !uiState.value.profile.isLoggedIn) return
        uiState.value = uiState.value.copy(isSyncing = true)
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncStatus.reportFailure(FriendlyErrors.of(e))
            } finally {
                uiState.value = uiState.value.copy(isSyncing = false)
            }
        }
    }
}
