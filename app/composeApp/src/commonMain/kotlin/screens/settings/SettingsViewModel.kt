package com.truyencloud.compose.screens.settings

import com.truyencloud.shared.api.AppUpdateApi
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.api.SessionState
import com.truyencloud.shared.api.SettingsApi
import com.truyencloud.shared.data.LocalDataMaintenance
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.ChapterUpdateChecker
import com.truyencloud.shared.repo.NovelRepository
import com.truyencloud.shared.repo.ReaderRepository
import com.truyencloud.shared.repo.SyncStatus
import com.truyencloud.shared.repo.UserRepository
import com.truyencloud.shared.tts.NoOpTtsSpeaker
import com.truyencloud.shared.tts.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val userRepository: UserRepository,
    private val ttsSpeaker: TtsSpeaker = NoOpTtsSpeaker(),
    private val settingsApi: SettingsApi = SettingsApi(),
    private val appUpdateApi: AppUpdateApi = AppUpdateApi(),
    private val chapterRepository: ChapterRepository? = null,
    private val readerRepository: ReaderRepository? = null,
    private val updateChecker: ChapterUpdateChecker? = null,
    /** Phụ thuộc cho các sheet mở từ tab Cài đặt (HomeScreen không truyền chúng xuống SettingsScreen). */
    val novelRepository: NovelRepository? = null,
    val savedWordDao: SavedWordDao? = null,
    dataMaintenance: LocalDataMaintenance? = null,
    /** "Đồng bộ ngay": đẩy/kéo dữ liệu đám mây. Null = không có nút. */
    private val syncNowAction: (suspend () -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val chapterRepositoryForSheets: ChapterRepository? get() = chapterRepository
    val storageCleaner: OfflineStorageCleaner? get() = storageCoordinator.cleaner

    /** Có hàm đồng bộ được nối vào không (ẩn nút "Đồng bộ ngay" nếu không). */
    val canSyncNow: Boolean get() = syncNowAction != null

    private val ttsCoordinator = SettingsTtsCoordinator(
        userRepository = userRepository,
        ttsSpeaker = ttsSpeaker,
        uiState = _uiState
    )

    private val translationCoordinator = SettingsTranslationCoordinator(
        settingsApi = settingsApi,
        uiState = _uiState,
        scope = scope,
        isAdmin = { _uiState.value.profile.isAdmin }
    )

    private val storageCoordinator = SettingsStorageCoordinator(
        chapterRepository = chapterRepository,
        uiState = _uiState,
        scope = scope,
        maintenance = dataMaintenance
    )

    private val notifyCoordinator = SettingsNotifyCoordinator(
        updateChecker = updateChecker,
        uiState = _uiState,
        scope = scope
    )

    private val accountCoordinator = SettingsAccountCoordinator(
        userRepository = userRepository,
        uiState = _uiState,
        scope = scope,
        syncNowAction = syncNowAction
    )

    private val serverCoordinator = SettingsServerCoordinator(
        userRepository = userRepository,
        uiState = _uiState,
        scope = scope,
        onServerChanged = {
            refreshEngineStatus()
            loadModels()
            loadTranslationProfile()
        }
    )

    private val updateCoordinator = SettingsUpdateCoordinator(
        appUpdateApi = appUpdateApi,
        userRepository = userRepository,
        uiState = _uiState,
        scope = scope
    )

    init {
        _uiState.value = _uiState.value.copy(
            ttsPlatform = ttsSpeaker.platform,
            ttsOfflineEngineName = ttsSpeaker.offlineEngineInfo,
            ttsOfflineVoiceName = ttsSpeaker.offlineVoiceName,
            ttsOfflineGuide = ttsSpeaker.offlineSetupGuide
        )
        refreshCacheSize()
        // Địa chỉ máy chủ đang dùng (Nâng cao → Máy chủ) và trạng thái đồng bộ THẬT (thay nhãn "Đã bật" cố định)
        scope.launch {
            userRepository.profile.collect { serverCoordinator.onSavedUrlChanged(it.serverUrl) }
        }
        scope.launch {
            // Phản hồi đồng bộ đến muộn có thể xoá (thành công) hoặc đặt (401 của token phiên cũ) cờ "hết hạn" của
            // SyncStatus sai lúc; trạng thái phiên (chỉ đổi khi đăng nhập/đăng xuất/bị từ chối) mới là nguồn đúng
            // cho câu hỏi "đã hết hạn chưa đăng nhập lại?".
            combine(SyncStatus.state, userRepository.sessionState) { sync, session ->
                val expired = session == SessionState.EXPIRED
                if (sync.sessionExpired == expired) sync else sync.copy(sessionExpired = expired)
            }.collect { _uiState.value = _uiState.value.copy(syncState = it) }
        }
        scope.launch {
            userRepository.profile.collect { p ->
                _uiState.value = _uiState.value.copy(
                    profile = p,
                    ttsSpeed = p.ttsSpeed,
                    ttsAutoScroll = p.ttsAutoScroll,
                    ttsVoiceTone = p.ttsVoiceTone,
                    ttsVoiceProfileId = p.ttsVoiceProfileId,
                    ttsAutoNextChapter = p.ttsAutoNextChapter,
                    ttsBackgroundPlayback = p.ttsBackgroundPlayback,
                    ttsOnlineMode = p.ttsOnlineMode
                )
            }
        }
        // Dong bo bo doc <-> cai dat: chi day khi chinh truong TTS doi, nghe thu tren bo phat rieng, loi bo doc
        ttsCoordinator.observe(scope)
        refreshEngineStatus()
        loadModels()
        loadTranslationProfile()
        refreshReadingStats()
        val vCode = com.truyencloud.shared.update.AppUpdateInstaller.getCurrentVersionCode()
        val vName = com.truyencloud.shared.update.AppUpdateInstaller.getCurrentVersionName()
        _uiState.value = _uiState.value.copy(
            currentAppVersionCode = vCode,
            currentAppVersionName = vName
        )
        notifyCoordinator.loadInitialState()
        checkForAppUpdates(silent = true)
    }

    // Báo chương mới (SettingsNotifyCoordinator)
    fun setNotifyNewChapters(enabled: Boolean) = notifyCoordinator.setNotifyNewChapters(enabled)
    fun checkUpdatesNow() = notifyCoordinator.checkUpdatesNow()

    fun refreshReadingStats() {
        val repo = readerRepository ?: return
        scope.launch {
            try {
                // Tính xong RỒI mới đọc-rồi-ghi trạng thái. Viết `_uiState.value = _uiState.value.copy(x = suspendCall())`
                // đọc trạng thái TRƯỚC khi gọi hàm treo, nên ghi đè mất mọi thay đổi xảy ra trong lúc chờ
                // (dung lượng, trạng thái đồng bộ, máy chủ... đều bị trả về giá trị cũ).
                val stats = repo.computeReadingStats()
                _uiState.value = _uiState.value.copy(readingStats = stats)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun refreshEngineStatus() {
        scope.launch {
            try {
                val res = settingsApi.getSettings()
                _uiState.value = _uiState.value.copy(
                    hasApiKey = res.has_api_key,
                    currentEngine = res.engine,
                    isAgyInstalled = res.agy_installed,
                    isAgyAuthenticated = res.agy_authenticated,
                    activeModel = res.active_model.ifBlank { _uiState.value.activeModel },
                    cliVersion = res.cli_version.ifBlank { _uiState.value.cliVersion },
                    serverStatus = "Máy chủ đang hoạt động"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(serverStatus = "Chưa kết nối được: ${FriendlyErrors.of(e)}")
            }
        }
    }

    // Translation Coordinator Delegation
    fun loadModels() = translationCoordinator.loadModels()
    fun refreshModels() = translationCoordinator.refreshModels()
    fun selectModel(modelId: String, effort: String? = null, timeoutSeconds: Int? = null) =
        translationCoordinator.selectModel(modelId, effort, timeoutSeconds)
    fun openModelPicker() = translationCoordinator.openModelPicker()
    fun dismissModelPicker() = translationCoordinator.dismissModelPicker()
    fun loadTranslationProfile() = translationCoordinator.loadTranslationProfile()
    fun onGenreChange(genre: String) = translationCoordinator.onGenreChange(genre)
    fun onSinoVietnameseRatioChange(ratio: String) = translationCoordinator.onSinoVietnameseRatioChange(ratio)
    fun onCustomPromptChange(prompt: String) = translationCoordinator.onCustomPromptChange(prompt)
    fun onPrefetchChaptersChange(count: Int) = translationCoordinator.onPrefetchChaptersChange(count)
    fun onAutoRecrawlChange(enabled: Boolean) = translationCoordinator.onAutoRecrawlChange(enabled)
    fun onReaderModeChange(mode: String) = translationCoordinator.onReaderModeChange(mode)
    fun saveTranslationProfile() = translationCoordinator.saveTranslationProfile()

    // Nâng cao → Máy chủ (SettingsServerCoordinator)
    fun onServerUrlChange(url: String) = serverCoordinator.onInputChange(url)
    fun testServerConnection() = serverCoordinator.testConnection()
    fun saveServerUrl() = serverCoordinator.save()
    fun resetServerUrl() = serverCoordinator.resetToDefault()

    fun onThemeChange(theme: String) {
        val current = _uiState.value.profile
        userRepository.updatePreferences(theme, current.readerFontSize, current.readerLineHeight)
    }

    fun onFontSizeChange(fontSize: Int) {
        val current = _uiState.value.profile
        userRepository.updatePreferences(current.preferredTheme, fontSize, current.readerLineHeight)
    }

    // Storage Coordinator Delegation
    fun requestClearCache() = storageCoordinator.requestClearAll()
    fun confirmClearCache() = storageCoordinator.confirmClear()
    fun dismissClearCache() = storageCoordinator.dismissConfirm()
    fun refreshCacheSize() = storageCoordinator.refreshCacheSize()
    fun openExportSheet() = storageCoordinator.openExportSheet()
    fun closeExportSheet() = storageCoordinator.closeExportSheet()
    fun openStorageManager() = storageCoordinator.openStorageManager()
    fun closeStorageManager() = storageCoordinator.closeStorageManager()

    // Profile Edit
    fun openEditProfile() {
        val p = _uiState.value.profile
        _uiState.value = _uiState.value.copy(
            isEditProfileOpen = true,
            editDisplayName = p.displayName,
            editBio = p.bio,
            editAvatarUrl = p.avatarUrl,
            editBannerUrl = p.bannerUrl
        )
    }

    fun dismissEditProfile() {
        _uiState.value = _uiState.value.copy(isEditProfileOpen = false)
    }

    fun onEditDisplayNameChange(name: String) {
        _uiState.value = _uiState.value.copy(editDisplayName = name)
    }

    fun onEditBioChange(bio: String) {
        _uiState.value = _uiState.value.copy(editBio = bio)
    }

    fun onEditAvatarUrlChange(url: String) {
        _uiState.value = _uiState.value.copy(editAvatarUrl = url)
    }

    fun saveProfile() {
        val s = _uiState.value
        userRepository.updateProfile(
            displayName = s.editDisplayName,
            bio = s.editBio,
            avatarUrl = s.editAvatarUrl,
            bannerUrl = s.editBannerUrl
        )
        _uiState.value = _uiState.value.copy(isEditProfileOpen = false)
    }

    // TTS Coordinator Delegation
    fun onTtsSpeedChange(speed: Float) = ttsCoordinator.onTtsSpeedChange(speed)
    fun onTtsAutoScrollChange(enabled: Boolean) = ttsCoordinator.onTtsAutoScrollChange(enabled)
    fun onTtsAutoNextChange(enabled: Boolean) = ttsCoordinator.onTtsAutoNextChange(enabled)
    fun onTtsBackgroundPlaybackChange(enabled: Boolean) = ttsCoordinator.onTtsBackgroundPlaybackChange(enabled)
    fun onTtsOnlineModeChange(enabled: Boolean) = ttsCoordinator.onTtsOnlineModeChange(enabled)
    fun refreshOfflineVoice() = ttsCoordinator.refreshOfflineVoice()
    fun refreshVoiceCatalog() = ttsCoordinator.refreshVoiceCatalog(scope)
    fun onTtsVoiceProfileChange(profileId: String) = ttsCoordinator.onTtsVoiceProfileChange(profileId)
    fun testTtsVoice() = ttsCoordinator.testTtsVoice()

    // Tài khoản (SettingsAccountCoordinator): đăng xuất có lựa chọn giữ/xoá dữ liệu, đổi mật khẩu, đồng bộ ngay
    fun requestLogout() = accountCoordinator.requestLogout()
    fun dismissLogout() = accountCoordinator.dismissLogout()
    fun confirmLogout(wipeLocalData: Boolean, onComplete: () -> Unit) =
        accountCoordinator.confirmLogout(wipeLocalData, onComplete)
    fun openChangePassword() = accountCoordinator.openChangePassword()
    fun closeChangePassword() = accountCoordinator.closeChangePassword()
    fun onOldPasswordChange(value: String) = accountCoordinator.onOldPasswordChange(value)
    fun onNewPasswordChange(value: String) = accountCoordinator.onNewPasswordChange(value)
    fun onConfirmPasswordChange(value: String) = accountCoordinator.onConfirmPasswordChange(value)
    fun submitChangePassword() = accountCoordinator.submitChangePassword()
    fun syncNow() = accountCoordinator.syncNow()

    // App OTA Update Actions (Delegated to SettingsUpdateCoordinator)
    fun checkForAppUpdates(silent: Boolean = false) = updateCoordinator.checkForAppUpdates(silent)
    fun openAppUpdateDialog() = updateCoordinator.openAppUpdateDialog()
    fun dismissAppUpdateDialog() = updateCoordinator.dismissAppUpdateDialog()
    fun startAppUpdateDownload() = updateCoordinator.startAppUpdateDownload()
}
