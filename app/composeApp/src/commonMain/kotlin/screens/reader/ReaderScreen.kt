package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.platform.KeepScreenOn
import com.truyencloud.compose.platform.ReaderHardwareKeys
import com.truyencloud.compose.theme.*
import com.truyencloud.shared.reader.ReaderVolumeKeys
import com.truyencloud.shared.repo.LifecycleSyncManager

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Quản lý bộ đếm thời gian đọc và đồng bộ vòng đời (Lifecycle Auto-Sync)
    DisposableEffect(Unit) {
        viewModel.setReaderVisible(true)
        LifecycleSyncManager.registerReaderFlush {
            viewModel.flushReadingProgressImmediately()
        }
        onDispose {
            LifecycleSyncManager.unregisterReaderFlush()
            viewModel.setReaderVisible(false)
            viewModel.flushReadingProgressImmediately()
        }
    }

    // Giữ màn hình sáng khi đọc (cài đặt "Giữ màn hình sáng khi đọc", mặc định bật)
    KeepScreenOn(enabled = uiState.keepScreenOn)

    // Phím âm lượng lật trang: chỉ bật khi màn đọc đang hiện, người dùng đã bật tuỳ chọn, KHÔNG bảng / hộp thoại nào che phía trước
    // (kể cả Trợ lý, Audiobook, dấu trang...: phím không được lật cuốn sách phía sau hay nuốt thanh âm lượng) và đang có chữ để lật
    val volumePaging = ReaderVolumeKeys.pagingActive(
        optionOn = uiState.volumeKeyPaging,
        overlayOpen = uiState.anyOverlayOpen,
        hasText = uiState.paragraphs.isNotEmpty()
    )
    DisposableEffect(volumePaging) {
        ReaderHardwareKeys.volumeKeyPagingEnabled = volumePaging
        onDispose { ReaderHardwareKeys.volumeKeyPagingEnabled = false }
    }

    val chapter = uiState.currentChapter
    // Chưa có chữ để đọc (đang tải / lỗi / đang dịch): luôn hiện thanh trên-dưới để còn đường quay lại, mở mục lục, dịch lại
    val barsVisible = uiState.showBars || uiState.paragraphs.isEmpty()

    AppTheme(themeMode = uiState.themeMode) {
        val colors = LocalCustomColors.current

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.bg)
        ) {
            // Main content: màn chờ / thẻ dịch / thẻ lỗi / nội dung chương
            ReaderBody(state = uiState, viewModel = viewModel)

            // Software Dimmer Scrim (đèn đọc đêm mượt mà từ 0.15f đến 1.0f)
            if (uiState.readerBrightness < 0.99f) {
                val dimmerAlpha = ((1f - uiState.readerBrightness) * 0.78f).coerceIn(0f, 0.72f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = dimmerAlpha))
                )
            }

            // Left Edge Brightness Gesture Zone (Vuốt dọc cạnh trái để tăng/giảm độ sáng)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(48.dp)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            viewModel.adjustBrightnessDelta(-delta / 380f)
                        }
                    )
            )

            // Brightness HUD Badge
            ReaderBrightnessHud(
                visible = uiState.showBrightnessHud,
                brightness = uiState.readerBrightness,
                percent = uiState.brightnessHudPercent,
                modifier = Modifier.align(Alignment.Center)
            )

            // Tiến trình tải chương / lỗi tải / đang dịch / lỗi dịch / bản dịch tạm (khi vẫn có chữ để đọc)
            ReaderStatusLayer(
                state = uiState,
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // Background translation badge (nếu đang dịch ngầm chương khác)
            val otherChapter = uiState.translatingChapterNumber
            if (uiState.isTranslating && otherChapter != null && otherChapter != chapter?.chapterNumber) {
                ReaderBackgroundTranslationBadge(
                    chapterNumber = otherChapter,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = if (barsVisible) Spacing.topBarHeight + Spacing.md else Spacing.sm, end = Spacing.lg)
                )
            }

            // Top Bar
            ReaderTopBar(
                title = chapter?.let { it.titleVi.ifBlank { it.title } } ?: "Đang đọc",
                visible = barsVisible,
                isPaginatedMode = uiState.isPaginatedMode,
                isEnglishMode = uiState.isEnglishMode,
                isBilingualMode = uiState.isBilingualMode,
                isProgressiveMode = uiState.isProgressiveMode,
                englishLevel = uiState.englishLevel,
                onCycleLanguageMode = viewModel::cycleLanguageMode,
                onBack = onBack,
                onOpenToc = viewModel::toggleQuickToc,
                onOpenBookmarks = viewModel::openBookmarksSheet,
                onOpenFind = viewModel::openFindSheet,
                bookmarkCount = uiState.chapterBookmarks.size,
                onTogglePaginatedMode = viewModel::togglePaginatedMode,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // Bottom Bar & TTS Bar
            Column(
                modifier = Modifier.align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                uiState.toastMessage?.let { ReaderToast(message = it, onDismiss = viewModel::dismissToast) }
                // Chuyển thể Anh / song ngữ thất bại (kể cả khách bật song ngữ): bản cũ chỉ ghi lỗi vào state nên người đọc thấy nút tự tắt mà không biết vì sao
                uiState.englishNotice?.let { ReaderToast(message = it, onDismiss = viewModel::dismissEnglishNotice) }

                // Floating TTS Player Bar if active
                if (uiState.isTtsActive) {
                    uiState.ttsError?.let { TtsErrorBanner(message = it) }
                    TtsControlBar(
                        isPlaying = uiState.ttsIsPlaying,
                        currentParagraph = uiState.ttsCurrentParagraph,
                        totalParagraphs = uiState.ttsSource().size,
                        speed = uiState.ttsSpeed,
                        sleepTimerMode = uiState.sleepTimerMode,
                        sleepTimerRemainingSeconds = uiState.sleepTimerRemainingSeconds,
                        ambientSoundType = uiState.ambientSoundType,
                        onPlayPause = viewModel::toggleTtsPlayPause,
                        onPrevParagraph = viewModel::prevTtsParagraph,
                        onNextParagraph = viewModel::nextTtsParagraph,
                        onCycleSpeed = viewModel::cycleTtsSpeed,
                        onOpenStudio = viewModel::openAudiobookStudio,
                        onCloseTts = viewModel::closeTts,
                        respectNavigationBar = !barsVisible,
                        preparingLabel = uiState.ttsPreparingLabel,
                        deviceFallback = uiState.ttsDeviceFallback && uiState.ttsOnlineMode
                    )
                }

                // Thanh điều khiển cuộn tự động: nằm TRÊN thanh dưới khi thanh dưới đang hiện (thanh dưới đã chừa vùng điều hướng)
                if (uiState.isAutoScrolling) {
                    AutoScrollControlBar(
                        visible = true,
                        isPaused = uiState.isAutoScrollPaused,
                        speed = uiState.autoScrollSpeed,
                        onTogglePause = { viewModel.setAutoScrollPaused(!uiState.isAutoScrollPaused) },
                        onCycleSpeed = viewModel::cycleAutoScrollSpeed,
                        onClose = viewModel::stopAutoScroll,
                        respectNavigationBar = !barsVisible
                    )
                }

                ReaderBottomBar(
                    visible = barsVisible,
                    hasTranslation = !chapter?.translatedContent.isNullOrBlank(),
                    showOriginal = uiState.showOriginal,
                    isTranslating = uiState.isTranslating &&
                        (uiState.translatingChapterNumber == null || uiState.translatingChapterNumber == chapter?.chapterNumber),
                    isPaginatedMode = uiState.isPaginatedMode,
                    isAutoScrolling = uiState.isAutoScrolling,
                    onPrev = viewModel::prevChapter,
                    onNext = viewModel::nextChapter,
                    onTranslate = viewModel::triggerTranslation,
                    onToggleOriginal = viewModel::toggleOriginal,
                    onToggleTts = viewModel::startTts,
                    onToggleAutoScroll = viewModel::toggleAutoScroll,
                    onOpenSettings = viewModel::toggleSettingsSheet
                )
            }

            // Quick TOC Sheet Backdrop & Sheet
            ReaderQuickTocHost(state = uiState, viewModel = viewModel)

            // Toàn bộ Modal Bottom Sheets & Hộp thoại điều khiển
            ReaderModals(
                viewModel = viewModel,
                uiState = uiState,
                chapter = chapter
            )
        }
    }
}
