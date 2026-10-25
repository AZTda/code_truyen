package com.truyencloud.compose.screens.settings

import com.truyencloud.shared.api.ErrorContext
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.api.SettingsApi
import com.truyencloud.shared.api.TranslationProfileResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Model dịch và hồ sơ văn phong. Đây là cấu hình DÙNG CHUNG trên máy chủ: máy chủ chỉ cho quản trị viên
 * ghi (403 với người dùng thường), nên mọi thao tác ghi ở đây tự chặn khi [isAdmin] trả false — người dùng
 * thường không bao giờ gửi một yêu cầu chắc chắn bị từ chối, và không thấy lỗi "Lỗi lưu cấu hình: 403".
 */
class SettingsTranslationCoordinator(
    private val settingsApi: SettingsApi,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val scope: CoroutineScope,
    private val isAdmin: () -> Boolean = { true }
) {

    fun loadModels() {
        scope.launch {
            try {
                val cat = settingsApi.getModels()
                uiState.value = uiState.value.copy(
                    models = cat.models,
                    activeModel = cat.active_model,
                    activeEffort = cat.active_effort,
                    timeoutSeconds = cat.timeout_seconds,
                    cliVersion = cat.cli_version,
                    lastModelUpdate = cat.last_updated
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun refreshModels() {
        if (!isAdmin()) return denyNonAdmin()
        scope.launch {
            uiState.value = uiState.value.copy(isRefreshingModels = true, modelStatusMessage = "")
            try {
                val cat = settingsApi.refreshModels()
                uiState.value = uiState.value.copy(
                    models = cat.models,
                    activeModel = cat.active_model,
                    activeEffort = cat.active_effort,
                    timeoutSeconds = cat.timeout_seconds,
                    cliVersion = cat.cli_version,
                    lastModelUpdate = cat.last_updated,
                    isRefreshingModels = false,
                    modelStatusMessage = "Đã cập nhật ${cat.models.size} model từ Antigravity CLI (v${cat.cli_version})!"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    isRefreshingModels = false,
                    modelStatusMessage = "Không cập nhật được: ${FriendlyErrors.of(e, ErrorContext.ADMIN_ACTION)}"
                )
            }
        }
    }

    fun selectModel(modelId: String, effort: String? = null, timeoutSeconds: Int? = null) {
        if (!isAdmin()) return denyNonAdmin()
        scope.launch {
            try {
                val cat = settingsApi.selectModel(
                    modelId = modelId,
                    effort = effort ?: uiState.value.activeEffort,
                    timeoutSeconds = timeoutSeconds ?: uiState.value.timeoutSeconds
                )
                uiState.value = uiState.value.copy(
                    activeModel = cat.active_model,
                    activeEffort = cat.active_effort,
                    timeoutSeconds = cat.timeout_seconds,
                    isModelPickerOpen = false,
                    modelStatusMessage = "Đã chọn model dịch: ${cat.active_model}"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    modelStatusMessage = "Không chọn được model: ${FriendlyErrors.of(e, ErrorContext.ADMIN_ACTION)}"
                )
            }
        }
    }

    fun openModelPicker() {
        // Người dùng thường chỉ xem được danh mục (GET), không chọn được
        uiState.value = uiState.value.copy(isModelPickerOpen = true)
        if (uiState.value.models.isEmpty()) {
            loadModels()
        }
    }

    fun dismissModelPicker() {
        uiState.value = uiState.value.copy(isModelPickerOpen = false)
    }

    fun loadTranslationProfile() {
        scope.launch {
            try {
                val p = settingsApi.getTranslationProfile()
                uiState.value = uiState.value.copy(
                    selectedGenre = p.genre,
                    sinoVietnameseRatio = p.sino_vietnamese_ratio,
                    customPromptInput = p.custom_prompt,
                    prefetchChapters = p.prefetch_chapters,
                    autoRecrawl = p.auto_recrawl,
                    readerMode = p.reader_mode
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun onGenreChange(genre: String) {
        if (!isAdmin()) return denyNonAdmin()
        uiState.value = uiState.value.copy(selectedGenre = genre)
        saveTranslationProfile()
    }

    fun onSinoVietnameseRatioChange(ratio: String) {
        if (!isAdmin()) return denyNonAdmin()
        uiState.value = uiState.value.copy(sinoVietnameseRatio = ratio)
        saveTranslationProfile()
    }

    fun onCustomPromptChange(prompt: String) {
        if (!isAdmin()) return denyNonAdmin()
        uiState.value = uiState.value.copy(customPromptInput = prompt)
    }

    fun onPrefetchChaptersChange(count: Int) {
        if (!isAdmin()) return denyNonAdmin()
        uiState.value = uiState.value.copy(prefetchChapters = count)
        saveTranslationProfile()
    }

    fun onAutoRecrawlChange(enabled: Boolean) {
        if (!isAdmin()) return denyNonAdmin()
        uiState.value = uiState.value.copy(autoRecrawl = enabled)
        saveTranslationProfile()
    }

    fun onReaderModeChange(mode: String) {
        if (!isAdmin()) return denyNonAdmin()
        uiState.value = uiState.value.copy(readerMode = mode)
        saveTranslationProfile()
    }

    fun saveTranslationProfile() {
        if (!isAdmin()) return denyNonAdmin()
        scope.launch {
            uiState.value = uiState.value.copy(isSavingProfile = true)
            try {
                settingsApi.updateTranslationProfile(
                    TranslationProfileResponse(
                        genre = uiState.value.selectedGenre,
                        sino_vietnamese_ratio = uiState.value.sinoVietnameseRatio,
                        custom_prompt = uiState.value.customPromptInput,
                        prefetch_chapters = uiState.value.prefetchChapters,
                        auto_recrawl = uiState.value.autoRecrawl,
                        reader_mode = uiState.value.readerMode
                    )
                )
                uiState.value = uiState.value.copy(
                    isSavingProfile = false,
                    profileSavedMessage = "Đã lưu hồ sơ dịch thành công!"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    isSavingProfile = false,
                    profileSavedMessage = "Lỗi lưu cấu hình: ${FriendlyErrors.of(e, ErrorContext.ADMIN_ACTION)}"
                )
            }
        }
    }

    private fun denyNonAdmin() {
        uiState.value = uiState.value.copy(
            modelStatusMessage = FriendlyErrors.MSG_ADMIN_ONLY,
            profileSavedMessage = "Lỗi: ${FriendlyErrors.MSG_ADMIN_ONLY}"
        )
    }
}
