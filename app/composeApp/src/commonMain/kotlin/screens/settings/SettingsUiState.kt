package com.truyencloud.compose.screens.settings

import com.truyencloud.shared.api.AppVersionResponse
import com.truyencloud.shared.api.ModelItem
import com.truyencloud.shared.data.UserProfile
import com.truyencloud.shared.repo.ReadingStats
import com.truyencloud.shared.repo.SyncStatus
import com.truyencloud.shared.tts.TtsVoiceCatalog
import com.truyencloud.shared.tts.TtsPlatform
import com.truyencloud.shared.tts.TtsVoiceProfile

data class SettingsUiState(
    val profile: UserProfile = UserProfile(),
    // Máy chủ (Nâng cao)
    val server: ServerSettingsState = ServerSettingsState(),
    val serverStatus: String = "",

    // Tài khoản & đồng bộ
    val syncState: SyncStatus.State = SyncStatus.State(),
    val isSyncing: Boolean = false,
    val isLogoutDialogOpen: Boolean = false,
    val changePassword: ChangePasswordState = ChangePasswordState(),

    val currentEngine: String = "ANTIGRAVITY_CLI",
    val hasApiKey: Boolean = false,
    val isAgyInstalled: Boolean = false,
    val isAgyAuthenticated: Boolean = false,
    val cacheSize: String = "—",
    val cacheChapterCount: Int = 0,
    val isCacheCleared: Boolean = false,
    val isClearingStorage: Boolean = false,
    val storageConfirm: StorageConfirm? = null,
    val cacheMessage: String = "",
    val isEditProfileOpen: Boolean = false,
    val editDisplayName: String = "",
    val editBio: String = "",
    val editAvatarUrl: String = "",
    // Giao diện không còn ô sửa banner; giữ giá trị cũ để lưu hồ sơ không làm mất ảnh nền đã có
    val editBannerUrl: String = "",

    // Model Catalog State
    val models: List<ModelItem> = emptyList(),
    val activeModel: String = "gemini-3.8-flash-medium",
    val activeEffort: String = "medium",
    val timeoutSeconds: Int = 900,
    val cliVersion: String = "1.1.27",
    val lastModelUpdate: String = "",
    val isRefreshingModels: Boolean = false,
    val isModelPickerOpen: Boolean = false,
    val modelStatusMessage: String = "",

    // Translation Profile State
    val selectedGenre: String = "Tiên hiệp / Huyền huyễn",
    val sinoVietnameseRatio: String = "Cân bằng tự nhiên",
    val customPromptInput: String = "Dịch giọng văn tiểu thuyết trôi chảy, xưng hô phù hợp bối cảnh, giữ nguyên kiếm chiêu và danh xưng tôn kính.",
    val prefetchChapters: Int = 1,
    val autoRecrawl: Boolean = true,
    val readerMode: String = "translated",
    val isSavingProfile: Boolean = false,
    val profileSavedMessage: String = "",

    // Voice Reading (TTS) State
    val ttsSpeed: Float = 1.0f,
    val ttsAutoScroll: Boolean = true,
    val ttsVoiceTone: String = "Mỹ Duyên",
    val ttsVoiceProfileId: String = "my_duyen",
    val voiceProfiles: List<TtsVoiceProfile> = TtsVoiceCatalog.voices,
    val ttsAutoNextChapter: Boolean = true,
    val ttsBackgroundPlayback: Boolean = true,
    val ttsOnlineMode: Boolean = true,
    val isTtsTesting: Boolean = false,
    val ttsStatusMessage: String = "",
    val ttsPlatform: TtsPlatform = TtsPlatform.ANDROID,
    val ttsOfflineEngineName: String = "Android Text-to-Speech (Google / Samsung)",
    val ttsOfflineVoiceName: String = "Tiếng Việt (vi-VN)",
    val ttsOfflineGuide: String = "",

    val readingStats: ReadingStats = ReadingStats(),

    // Bao chuong moi
    val notifyNewChapters: Boolean = false,
    val notifyStatusMessage: String = "",
    val isCheckingUpdates: Boolean = false,

    // Vocabulary Export
    val isExportSheetOpen: Boolean = false,

    // Storage Manager (Offline UX)
    val isStorageManagerOpen: Boolean = false,

    // App OTA Update State (chỉ Android; iOS đặt otaSupported = false và không có luồng này)
    val otaSupported: Boolean = true,
    val currentAppVersionName: String = "1.0.0",
    val currentAppVersionCode: Int = 1,
    val isCheckingAppUpdate: Boolean = false,
    val appUpdateStatusMessage: String? = null,
    val appUpdateInfo: AppVersionResponse? = null,
    val isAppUpdateDialogOpen: Boolean = false,
    val isUpdateMandatory: Boolean = false,
    val isDownloadingApk: Boolean = false,
    val downloadApkProgress: Float = 0f,
    val downloadApkError: String? = null
) {
    /** Máy chủ có bản mới hơn bản đang chạy (luôn false trên iOS). */
    val hasAppUpdate: Boolean get() = otaSupported && (appUpdateInfo?.versionCode ?: 0) > currentAppVersionCode
}
