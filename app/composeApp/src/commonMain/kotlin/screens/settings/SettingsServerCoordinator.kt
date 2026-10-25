package com.truyencloud.compose.screens.settings

import com.truyencloud.shared.api.CleartextPolicies
import com.truyencloud.shared.api.CleartextPolicy
import com.truyencloud.shared.api.ConnectionTester
import com.truyencloud.shared.api.ServerUrlCheck
import com.truyencloud.shared.api.ServerUrlValidator
import com.truyencloud.shared.repo.UserRepository
import com.truyencloud.shared.update.AppUpdateInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * "Nâng cao → Máy chủ": nhập địa chỉ, kiểm tra định dạng, thử kết nối (GET /v1/health), lưu và đặt lại mặc định.
 */
class SettingsServerCoordinator(
    private val userRepository: UserRepository,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val scope: CoroutineScope,
    /** Gọi sau khi địa chỉ thật sự đổi — để nạp lại trạng thái engine, danh mục model... từ máy chủ mới. */
    private val onServerChanged: () -> Unit,
    private val cleartext: CleartextPolicy = CleartextPolicies.forPlatform(AppUpdateInstaller.platform)
) {
    private fun update(transform: (ServerSettingsState) -> ServerSettingsState) {
        uiState.value = uiState.value.copy(server = transform(uiState.value.server))
    }

    /** Đồng bộ khi địa chỉ đang dùng đổi từ nơi khác (hồ sơ nạp lên lúc khởi động). */
    fun onSavedUrlChanged(url: String) {
        update {
            if (it.savedUrl == url) it else it.copy(savedUrl = url, input = if (it.isDirty) it.input else url)
        }
    }

    fun onInputChange(text: String) {
        update {
            it.copy(
                input = text,
                validation = if (text.isBlank()) null else ServerUrlValidator.check(text, cleartext),
                testResult = null,
                notice = null
            )
        }
    }

    fun testConnection() {
        val current = uiState.value.server
        if (current.isTesting) return
        when (val check = ServerUrlValidator.check(current.input, cleartext)) {
            is ServerUrlCheck.Invalid -> update { it.copy(validation = check, testResult = null) }
            is ServerUrlCheck.Valid -> {
                update { it.copy(validation = check, isTesting = true, testResult = null, notice = null) }
                scope.launch {
                    // Thử đúng địa chỉ chuẩn hoá đang gõ, chưa cần lưu
                    val result = ConnectionTester.check(check.url)
                    update { it.copy(isTesting = false, testResult = result) }
                }
            }
        }
    }

    fun save() {
        when (val check = ServerUrlValidator.check(uiState.value.server.input, cleartext)) {
            is ServerUrlCheck.Invalid -> update { it.copy(validation = check) }
            is ServerUrlCheck.Valid -> apply(userRepository.updateServerUrl(check.url), check.url, check.warning)
        }
    }

    fun resetToDefault() {
        val sessionEnded = userRepository.resetServerUrl()
        apply(sessionEnded, userRepository.profile.value.serverUrl, warning = null)
    }

    private fun apply(sessionEnded: Boolean, url: String, warning: String?) {
        val notice = buildString {
            append("Đã lưu máy chủ: ").append(url).append('.')
            if (sessionEnded) {
                append(" Phiên đăng nhập cũ chỉ dùng được với máy chủ trước nên đã được ngắt — hãy đăng nhập lại.")
            }
            if (warning != null) append(' ').append(warning)
        }
        update {
            it.copy(input = url, savedUrl = url, validation = ServerUrlValidator.check(url, cleartext), testResult = null, notice = notice)
        }
        onServerChanged()
    }
}
