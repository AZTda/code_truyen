package com.truyencloud.compose.screens.settings

import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.notify.applyBackgroundUpdateChecks
import com.truyencloud.shared.notify.backgroundUpdateCheckDescription
import com.truyencloud.shared.notify.createNotifier
import com.truyencloud.shared.repo.ChapterUpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** "Báo khi truyện có chương mới": bật/tắt, xin quyền thông báo và kiểm tra ngay. */
class SettingsNotifyCoordinator(
    private val updateChecker: ChapterUpdateChecker?,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val scope: CoroutineScope
) {
    /** Nạp trạng thái ban đầu từ bộ kiểm tra chương mới (nếu có). */
    fun loadInitialState() {
        updateChecker?.let {
            uiState.value = uiState.value.copy(
                notifyNewChapters = it.isEnabled,
                notifyStatusMessage = backgroundUpdateCheckDescription()
            )
        }
    }

    fun setNotifyNewChapters(enabled: Boolean) {
        val checker = updateChecker ?: return
        if (!enabled) {
            checker.isEnabled = false
            applyBackgroundUpdateChecks(false)
            uiState.value = uiState.value.copy(notifyNewChapters = false, notifyStatusMessage = "")
            return
        }
        scope.launch {
            val notifier = createNotifier()
            val granted = notifier.hasPermission || notifier.requestPermission()
            if (!granted) {
                uiState.value = uiState.value.copy(
                    notifyNewChapters = false,
                    notifyStatusMessage = "Chưa được phép hiện thông báo — hãy bật trong Cài đặt của máy."
                )
                return@launch
            }
            checker.isEnabled = true
            applyBackgroundUpdateChecks(true)
            uiState.value = uiState.value.copy(
                notifyNewChapters = true,
                notifyStatusMessage = backgroundUpdateCheckDescription()
            )
        }
    }

    fun checkUpdatesNow() {
        val checker = updateChecker ?: return
        if (uiState.value.isCheckingUpdates) return
        uiState.value = uiState.value.copy(isCheckingUpdates = true)
        scope.launch {
            val message = try {
                when (val n = checker.runCheck(force = true)) {
                    0 -> "Chưa có chương mới nào."
                    else -> "Đã báo $n truyện có chương mới."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Không kiểm tra được: ${FriendlyErrors.of(e)}"
            }
            uiState.value = uiState.value.copy(isCheckingUpdates = false, notifyStatusMessage = message)
        }
    }
}
