package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.LargeTitleScaffold
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.NovelRepository

/**
 * Tab Cài đặt: tiêu đề lớn "Cài đặt" rồi các nhóm danh sách inset (hồ sơ, số liệu đọc, tài khoản, đọc truyện, giọng đọc,
 * đồng bộ và dữ liệu, dịch và AI, cập nhật ứng dụng, nâng cao). Cài đặt là một tab của HomeScreen nên không có nút quay lại.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onLogout: () -> Unit,
    savedWordDao: SavedWordDao? = null,
    chapterRepository: ChapterRepository? = null,
    novelRepository: NovelRepository? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAdmin = uiState.profile.isAdmin

    // Số liệu đọc và dung lượng chỉ được tính một lần lúc tạo ViewModel; nạp lại mỗi lần mở Cài đặt, nếu không
    // chúng lỗi thời sau khi đổi tài khoản hoặc xoá dữ liệu trên máy.
    LaunchedEffect(Unit) {
        viewModel.refreshReadingStats()
        viewModel.refreshCacheSize()
        viewModel.refreshOfflineVoice()
        viewModel.refreshVoiceCatalog()
    }

    val dataActions = remember(viewModel, onLogout) {
        DataSyncActions(
            onRequestClearCache = viewModel::requestClearCache,
            onOpenStorageManager = viewModel::openStorageManager,
            onSyncNow = if (viewModel.canSyncNow) viewModel::syncNow else null,
            onExportVocabulary = viewModel::openExportSheet,
            onLogin = onLogout
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        LargeTitleScaffold(title = "Cài đặt") { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imeAboveTabBar()
                    .verticalScroll(rememberScrollState())
                    .padding(top = Spacing.xs, bottom = contentPadding.calculateBottomPadding() + Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.section)
            ) {
                UserProfileHeader(
                    profile = uiState.profile,
                    onEditProfile = viewModel::openEditProfile,
                    onLogin = onLogout
                )

                UserStatsCard(stats = uiState.readingStats)

                AccountSection(
                    isLoggedIn = uiState.profile.isLoggedIn,
                    onChangePassword = viewModel::openChangePassword,
                    onRequestLogout = viewModel::requestLogout
                )

                ReaderSection(
                    preferredTheme = uiState.profile.preferredTheme,
                    fontSize = uiState.profile.readerFontSize,
                    readerMode = uiState.readerMode,
                    onThemeChange = viewModel::onThemeChange,
                    onFontSizeChange = viewModel::onFontSizeChange,
                    onReaderModeChange = viewModel::onReaderModeChange,
                    isAdmin = isAdmin,
                    notifyNewChapters = uiState.notifyNewChapters,
                    onNotifyNewChaptersChange = viewModel::setNotifyNewChapters,
                    notifyStatusMessage = uiState.notifyStatusMessage,
                    onCheckUpdatesNow = viewModel::checkUpdatesNow,
                    isCheckingUpdates = uiState.isCheckingUpdates
                )

                TtsVoiceSection(
                    ttsSpeed = uiState.ttsSpeed,
                    ttsAutoScroll = uiState.ttsAutoScroll,
                    ttsVoiceTone = uiState.ttsVoiceTone,
                    ttsVoiceProfileId = uiState.ttsVoiceProfileId,
                    voiceProfiles = uiState.voiceProfiles,
                    ttsAutoNextChapter = uiState.ttsAutoNextChapter,
                    ttsBackgroundPlayback = uiState.ttsBackgroundPlayback,
                    ttsOnlineMode = uiState.ttsOnlineMode,
                    isTesting = uiState.isTtsTesting,
                    statusMessage = uiState.ttsStatusMessage,
                    platform = uiState.ttsPlatform,
                    offlineEngineName = uiState.ttsOfflineEngineName,
                    offlineVoiceName = uiState.ttsOfflineVoiceName,
                    offlineSetupGuide = uiState.ttsOfflineGuide,
                    onSpeedChange = viewModel::onTtsSpeedChange,
                    onAutoScrollChange = viewModel::onTtsAutoScrollChange,
                    onAutoNextChange = viewModel::onTtsAutoNextChange,
                    onBackgroundPlaybackChange = viewModel::onTtsBackgroundPlaybackChange,
                    onOnlineModeChange = viewModel::onTtsOnlineModeChange,
                    onVoiceProfileChange = viewModel::onTtsVoiceProfileChange,
                    onToggleTest = viewModel::testTtsVoice
                )

                DataSyncSection(state = uiState, actions = dataActions)

                TranslationAiSection(
                    isAdmin = isAdmin,
                    activeModel = uiState.activeModel,
                    models = uiState.models,
                    isRefreshingModels = uiState.isRefreshingModels,
                    modelStatusMessage = uiState.modelStatusMessage,
                    onOpenModelPicker = viewModel::openModelPicker,
                    onRefreshModels = viewModel::refreshModels,
                    selectedGenre = uiState.selectedGenre,
                    onGenreChange = viewModel::onGenreChange,
                    sinoVietnameseRatio = uiState.sinoVietnameseRatio,
                    onSinoVietnameseRatioChange = viewModel::onSinoVietnameseRatioChange,
                    customPrompt = uiState.customPromptInput,
                    onCustomPromptChange = viewModel::onCustomPromptChange,
                    onSaveProfile = viewModel::saveTranslationProfile,
                    isSavingProfile = uiState.isSavingProfile,
                    profileSavedMessage = uiState.profileSavedMessage,
                    prefetchChapters = uiState.prefetchChapters,
                    onPrefetchChange = viewModel::onPrefetchChaptersChange,
                    autoRecrawl = uiState.autoRecrawl,
                    onAutoRecrawlChange = viewModel::onAutoRecrawlChange
                )

                // Cập nhật tại chỗ chỉ có trên Android; iOS chỉ hiện số phiên bản ở cuối trang
                if (uiState.otaSupported) {
                    AppUpdateSection(
                        currentAppVersionName = uiState.currentAppVersionName,
                        currentAppVersionCode = uiState.currentAppVersionCode,
                        isCheckingAppUpdate = uiState.isCheckingAppUpdate,
                        hasAppUpdate = uiState.hasAppUpdate,
                        appUpdateStatusMessage = uiState.appUpdateStatusMessage,
                        onCheckAppUpdate = viewModel::openAppUpdateDialog
                    )
                }

                AdvancedSection(
                    server = uiState.server,
                    engineStatus = uiState.serverStatus,
                    onServerInputChange = viewModel::onServerUrlChange,
                    onServerTest = viewModel::testServerConnection,
                    onServerSave = viewModel::saveServerUrl,
                    onServerReset = viewModel::resetServerUrl,
                    activeModel = uiState.activeModel,
                    activeEffort = uiState.activeEffort,
                    timeoutSeconds = uiState.timeoutSeconds,
                    cliVersion = uiState.cliVersion,
                    isAgyAuthenticated = uiState.isAgyAuthenticated,
                    isAdmin = isAdmin,
                    onSelectEffort = { effort -> viewModel.selectModel(uiState.activeModel, effort = effort) },
                    onSelectTimeout = { seconds -> viewModel.selectModel(uiState.activeModel, timeoutSeconds = seconds) }
                )

                VersionFooter(
                    versionName = uiState.currentAppVersionName,
                    versionCode = uiState.currentAppVersionCode
                )
            }
        }

        SettingsOverlays(
            viewModel = viewModel,
            uiState = uiState,
            onLogout = onLogout,
            savedWordDao = savedWordDao,
            chapterRepository = chapterRepository,
            novelRepository = novelRepository
        )
    }
}

/**
 * Chừa chỗ cho bàn phím nằm phía TRÊN thanh tab dưới của HomeScreen: thanh tab vẫn nằm sau bàn phím nên chỉ phần bàn phím cao hơn
 * (thanh điều hướng hệ thống + thanh tab) mới che nội dung. Cùng cách tính với `imeAboveTabBar` của màn Home; chép lại ở đây để
 * Cài đặt không phụ thuộc vào tệp của màn khác.
 */
@Composable
private fun Modifier.imeAboveTabBar(): Modifier {
    val ime = WindowInsets.ime
    val navigationBars = WindowInsets.navigationBars
    val insets = remember(ime, navigationBars) {
        ime.exclude(navigationBars).exclude(WindowInsets(bottom = Spacing.bottomBarHeight))
    }
    return windowInsetsPadding(insets)
}
