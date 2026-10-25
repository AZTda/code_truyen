package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.repo.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ReaderSettingsCoordinator(
    private val userRepository: UserRepository?,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope
) {
    private var brightnessSaveJob: Job? = null

    fun setup() {
        userRepository?.let { repo ->
            // Độ sáng: chỉ nạp MỘT lần lúc mở. Lưu theo nhịp trễ nên nạp lại theo mỗi thay đổi hồ sơ sẽ kéo thanh độ sáng giật ngược.
            uiStateFlow.value = uiStateFlow.value.copy(readerBrightness = repo.profile.value.readerBrightness)
            scope.launch {
                repo.profile.collect { p ->
                    uiStateFlow.value = uiStateFlow.value.copy(
                        fontSize = p.readerFontSize,
                        lineHeight = p.readerLineHeight,
                        lineSpacingFactor = p.readerLineHeight,
                        themeMode = p.preferredTheme,
                        isPaginatedMode = p.readerPaginated,
                        pageMargin = p.readerPageMargin,
                        fontFamilyType = p.readerFontFamily,
                        keepScreenOn = p.readerKeepScreenOn,
                        volumeKeyPaging = p.readerVolumeKeyPaging
                    )
                }
            }
        }
    }

    fun setFontSize(size: Int) {
        val clamped = size.coerceIn(12, 32)
        uiStateFlow.value = uiStateFlow.value.copy(fontSize = clamped)
        userRepository?.let { repo ->
            val p = repo.profile.value
            repo.updatePreferences(p.preferredTheme, clamped, p.readerLineHeight)
        }
    }

    fun setThemeMode(mode: String) {
        uiStateFlow.value = uiStateFlow.value.copy(themeMode = mode)
        userRepository?.let { repo ->
            val p = repo.profile.value
            repo.updatePreferences(mode, p.readerFontSize, p.readerLineHeight)
        }
    }

    fun toggleSettingsSheet() { uiStateFlow.value = uiStateFlow.value.copy(showSettingsSheet = !uiStateFlow.value.showSettingsSheet) }

    fun setFontFamilyType(type: String) {
        uiStateFlow.value = uiStateFlow.value.copy(fontFamilyType = type)
        userRepository?.updateReaderFontFamily(type)
    }

    fun setLineSpacingFactor(factor: Float) {
        val clamped = factor.coerceIn(1.2f, 2.6f)
        uiStateFlow.value = uiStateFlow.value.copy(lineSpacingFactor = clamped, lineHeight = clamped)
        userRepository?.let { repo ->
            val p = repo.profile.value
            repo.updatePreferences(p.preferredTheme, p.readerFontSize, clamped)
        }
    }

    fun togglePaginatedMode(paginated: Boolean? = null) {
        val next = paginated ?: !uiStateFlow.value.isPaginatedMode
        uiStateFlow.value = uiStateFlow.value.copy(isPaginatedMode = next)
        userRepository?.updateReaderPaginated(next)
    }

    fun setPageMargin(margin: Int) {
        uiStateFlow.value = uiStateFlow.value.copy(pageMargin = margin)
        userRepository?.updateReaderPageMargin(margin)
    }

    /** "Giữ màn hình sáng khi đọc" (mặc định bật). */
    fun setKeepScreenOn(enabled: Boolean) {
        uiStateFlow.value = uiStateFlow.value.copy(keepScreenOn = enabled)
        userRepository?.updateReaderKeepScreenOn(enabled)
    }

    /** "Lật trang bằng phím âm lượng" (mặc định tắt). */
    fun setVolumeKeyPaging(enabled: Boolean) {
        uiStateFlow.value = uiStateFlow.value.copy(volumeKeyPaging = enabled)
        userRepository?.updateReaderVolumeKeyPaging(enabled)
    }

    /** Ghi độ sáng xuống hồ sơ sau khi người đọc ngừng vuốt (vuốt phát ~60 sự kiện/giây, không ghi đĩa mỗi lần). */
    fun persistBrightnessSoon() {
        val repo = userRepository ?: return
        brightnessSaveJob?.cancel()
        brightnessSaveJob = scope.launch {
            delay(BRIGHTNESS_SAVE_DELAY_MS)
            repo.updateReaderBrightness(uiStateFlow.value.readerBrightness)
        }
    }

    fun applySettings(
        themeMode: String, fontSize: Int, fontFamilyType: String, lineSpacingFactor: Float,
        isPaginatedMode: Boolean, pageMargin: Int, isBilingualMode: Boolean, isEnglishMode: Boolean,
        isProgressiveMode: Boolean, englishLevel: String, progressiveRatio: Double
    ) {
        setFontSize(fontSize)
        setThemeMode(themeMode)
        setFontFamilyType(fontFamilyType)
        setLineSpacingFactor(lineSpacingFactor)
        setPageMargin(pageMargin)
        if (uiStateFlow.value.isPaginatedMode != isPaginatedMode) togglePaginatedMode(isPaginatedMode)
        uiStateFlow.value = uiStateFlow.value.copy(
            isBilingualMode = isBilingualMode, isEnglishMode = isEnglishMode,
            isProgressiveMode = isProgressiveMode, englishLevel = englishLevel, progressiveRatio = progressiveRatio
        )
    }

    private companion object {
        const val BRIGHTNESS_SAVE_DELAY_MS = 600L
    }
}
