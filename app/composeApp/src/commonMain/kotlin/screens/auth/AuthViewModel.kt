package com.truyencloud.compose.screens.auth

import com.truyencloud.shared.api.ErrorContext
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.repo.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isAuthenticated: Boolean = false
)

class AuthViewModel(
    private val userRepository: UserRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun reset() {
        _uiState.value = AuthUiState()
    }

    fun continueAsGuest() {
        userRepository.continueAsGuest()
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isAuthenticated = true,
            successMessage = "Đang vào chế độ khách. Bạn có thể đăng nhập sau để đồng bộ dữ liệu."
        )
    }

    fun onEmailChange(email: String) {
        _uiState.value = _uiState.value.copy(email = email, errorMessage = null)
    }

    fun onPasswordChange(password: String) {
        _uiState.value = _uiState.value.copy(password = password, errorMessage = null)
    }

    fun submit() {
        val state = _uiState.value
        if (state.isLoading) return
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Vui lòng nhập đầy đủ tên đăng nhập và mật khẩu.")
            return
        }

        scope.launch {
            _uiState.value = state.copy(isLoading = true, errorMessage = null)
            val result = userRepository.loginWithEmail(state.email.trim(), state.password)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isAuthenticated = true,
                    successMessage = result.getOrNull()
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    // Lỗi kỹ thuật (Unable to resolve host, timeout, 429/401/5xx...) → câu tiếng Việt dễ hiểu
                    errorMessage = result.exceptionOrNull()
                        ?.let { FriendlyErrors.of(it, ErrorContext.LOGIN) }
                        ?: "Đăng nhập thất bại."
                )
            }
        }
    }
}
