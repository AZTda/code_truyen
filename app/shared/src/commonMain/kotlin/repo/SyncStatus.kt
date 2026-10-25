package com.truyencloud.shared.repo

import com.truyencloud.shared.data.getCurrentEpochMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Trạng thái đồng bộ THẬT để hiển thị trong Cài đặt (thay dòng "Đã bật" cố định).
 * Mọi luồng đồng bộ (tiến độ, bookmark, từ vựng) báo kết quả vào đây.
 */
object SyncStatus {
    data class State(
        val lastSuccessAtMillis: Long = 0L,
        val lastError: String? = null,
        val lastErrorAtMillis: Long = 0L,
        val sessionExpired: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun now(): Long = getCurrentEpochMillis()

    fun reportSuccess() {
        _state.value = _state.value.copy(lastSuccessAtMillis = now(), lastError = null, sessionExpired = false)
    }

    fun reportFailure(message: String) {
        _state.value = _state.value.copy(lastError = message, lastErrorAtMillis = now())
    }

    fun reportSessionExpired() {
        _state.value = _state.value.copy(sessionExpired = true, lastError = "Phiên đăng nhập đã hết hạn", lastErrorAtMillis = now())
    }

    fun reset() {
        _state.value = State()
    }
}
