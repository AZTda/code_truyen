package com.truyencloud.compose.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.runtime.*
import com.truyencloud.compose.components.MiniAudiobookBar
import com.truyencloud.compose.screens.auth.AuthScreen
import com.truyencloud.compose.screens.auth.AuthViewModel
import com.truyencloud.compose.screens.detail.NovelDetailScreen
import com.truyencloud.compose.screens.detail.NovelDetailViewModel
import com.truyencloud.compose.screens.home.HomeScreen
import com.truyencloud.compose.screens.home.HomeViewModel
import com.truyencloud.compose.screens.reader.ReaderScreen
import com.truyencloud.compose.screens.reader.ReaderViewModel
import com.truyencloud.compose.screens.settings.SettingsViewModel
import com.truyencloud.compose.theme.AppTheme
import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.LocalDataMaintenance
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.data.ReadingSessionDao
import com.truyencloud.shared.data.ReviewLogDao
import com.truyencloud.shared.repo.*
import com.truyencloud.shared.tts.NoOpTtsSpeaker
import com.truyencloud.shared.tts.TtsSpeaker

sealed interface Screen {
    data object Auth : Screen
    data object Home : Screen
    data class Detail(val novel: Novel) : Screen
    data class Reader(val novel: Novel, val chapter: Chapter, val initialBilingualMode: Boolean = false) : Screen
}

@Composable
fun AppNav(
    novelRepository: NovelRepository,
    chapterRepository: ChapterRepository,
    translateRepository: TranslateRepository,
    readerRepository: ReaderRepository,
    userRepository: UserRepository,
    novelApi: NovelApi,
    vocabularyRepository: VocabularyRepository? = null,
    termMemoryRepository: TermMemoryRepository? = null,
    bookmarkRepository: BookmarkRepository? = null,
    savedWordDao: SavedWordDao? = null,
    readingSessionDao: ReadingSessionDao? = null,
    reviewLogDao: ReviewLogDao? = null,
    updateChecker: ChapterUpdateChecker? = null,
    /** Xoá dữ liệu người dùng + VACUUM: dùng cho "Xóa dữ liệu trên máy" và dọn chương đã tải. */
    dataMaintenance: LocalDataMaintenance? = null,
    ttsSpeaker: TtsSpeaker = NoOpTtsSpeaker(),
    initialScreen: Screen? = null,
    initialTab: com.truyencloud.compose.components.NavTab = com.truyencloud.compose.components.NavTab.BOOKSHELF
) {
    val profile = userRepository.profile.value
    val startScreen = initialScreen ?: if (profile.isLoggedIn || profile.authProvider == "guest") Screen.Home else Screen.Auth
    var currentScreen by remember { mutableStateOf<Screen>(startScreen) }

    // Bấm thông báo "chương mới" → mở thẳng trang truyện đó (truyện phải còn trong tủ sách)
    val requestedNovelId by NavigationRequests.openNovelId.collectAsState()
    LaunchedEffect(requestedNovelId) {
        val id = requestedNovelId ?: return@LaunchedEffect
        val novel = try { novelRepository.getNovelById(id) } catch (_: Exception) { null }
        NavigationRequests.consume(id)
        if (novel != null && currentScreen !is Screen.Auth) currentScreen = Screen.Detail(novel)
    }

    // Nguồn "Từ hôm nay" của Tủ sách (kho từ vựng, tự cập nhật theo Room kể cả khi xoá dữ liệu / đổi tài khoản) và nơi đăng
    // ký DAO cho chuỗi ngày dùng chung (ActivityStreak)
    val gamificationRepository = remember(savedWordDao, readingSessionDao, reviewLogDao) {
        if (savedWordDao != null) {
            com.truyencloud.shared.repo.GamificationRepository(
                readingSessionDao = readingSessionDao,
                reviewLogDao = reviewLogDao,
                savedWordDao = savedWordDao
            )
        } else null
    }

    val homeViewModel = remember {
        HomeViewModel(
            novelRepository = novelRepository,
            readerRepository = readerRepository,
            novelApi = novelApi,
            gamificationRepository = gamificationRepository,
            // Khám phá và gợi ý tìm kiếm là của từng tài khoản: màn hình chính phải biết khi tài khoản đổi / dữ liệu bị xoá
            userRepository = userRepository,
            dataMaintenance = dataMaintenance,
            initialTab = initialTab
        )
    }
    val authViewModel = remember { AuthViewModel(userRepository) }
    val detailViewModel = remember {
        NovelDetailViewModel(
            novelRepository = novelRepository,
            chapterRepository = chapterRepository,
            novelApi = novelApi,
            translateRepository = translateRepository,
            readerRepository = readerRepository,
            termMemoryRepository = termMemoryRepository,
            vocabularyRepository = vocabularyRepository,
            userRepository = userRepository,
            ttsSpeaker = ttsSpeaker
        )
    }
    val readerViewModel = remember {
        ReaderViewModel(
            chapterRepository = chapterRepository,
            translateRepository = translateRepository,
            readerRepository = readerRepository,
            vocabularyRepository = vocabularyRepository,
            termMemoryRepository = termMemoryRepository,
            bookmarkRepository = bookmarkRepository,
            ttsSpeaker = ttsSpeaker,
            userRepository = userRepository,
            updateChecker = updateChecker
        )
    }
    val settingsViewModel = remember {
        SettingsViewModel(
            userRepository = userRepository,
            ttsSpeaker = ttsSpeaker,
            chapterRepository = chapterRepository,
            readerRepository = readerRepository,
            updateChecker = updateChecker,
            novelRepository = novelRepository,
            savedWordDao = savedWordDao,
            dataMaintenance = dataMaintenance,
            syncNowAction = syncNowAction(readerRepository, bookmarkRepository, vocabularyRepository)
        )
    }
    val settingsState by settingsViewModel.uiState.collectAsState()
    val readerState by readerViewModel.uiState.collectAsState()

    val miniPlayerSlot: @Composable () -> Unit = {
        MiniAudiobookBar(
            state = readerState,
            onOpenReader = { novel, chapter -> currentScreen = Screen.Reader(novel, chapter) },
            onPlayPause = readerViewModel::toggleTtsPlayPause,
            onPrevParagraph = readerViewModel::prevTtsParagraph,
            onNextParagraph = readerViewModel::nextTtsParagraph,
            onClose = readerViewModel::closeTts
        )
    }

    // Đăng xuất / đổi tài khoản phải đẩy nốt thay đổi chưa đồng bộ trước khi xoá token và dữ liệu; UserRepository không
    // biết các kho khác nên nối ở đây.
    LaunchedEffect(userRepository, readerRepository, bookmarkRepository, vocabularyRepository) {
        userRepository.attachPendingChanges(pendingChangesOf(readerRepository, bookmarkRepository, vocabularyRepository))
    }

    // Từ vựng kéo về khi mở ứng dụng và mỗi lần quay lại (cùng nhịp giãn cách với tiến độ / dấu trang), không chỉ sau
    // khi có thay đổi cục bộ — máy thứ hai nếu không sẽ thấy sổ từ trống / cũ cho tới lúc bấm tay.
    DisposableEffect(vocabularyRepository) {
        vocabularyRepository?.let { repo ->
            LifecycleSyncManager.registerForegroundSync { repo.syncIfLoggedIn() }
            repo.requestSync(0L)
        }
        onDispose { LifecycleSyncManager.unregisterForegroundSync() }
    }
    val vocabularyViewModel = remember(vocabularyRepository, ttsSpeaker) {
        vocabularyRepository?.let {
            com.truyencloud.compose.screens.vocabulary.VocabularyViewModel(
                vocabularyRepository = it,
                ttsSpeaker = ttsSpeaker,
                englishApi = com.truyencloud.shared.api.EnglishApi()
            )
        }
    }
    val insightsViewModel = remember(savedWordDao, readingSessionDao, reviewLogDao) {
        savedWordDao?.let { dao ->
            com.truyencloud.compose.screens.insights.InsightsViewModel(
                savedWordDao = dao,
                readingSessionDao = readingSessionDao,
                reviewLogDao = reviewLogDao,
                englishApi = com.truyencloud.shared.api.EnglishApi()
            )
        }
    }

    // Nut Back cua he thong.
    //
    // Truoc ban va nay KHONG co BackHandler nao trong toan du an: dang doc truyen
    // ma vuot canh man hinh la THOAT HAN ung dung, mat luon cho dang doc.
    PlatformBackHandler(enabled = currentScreen !is Screen.Home) {
        currentScreen = when (val s = currentScreen) {
            is Screen.Reader -> {
                readerViewModel.flushReadingProgressImmediately()
                Screen.Detail(s.novel)
            }
            is Screen.Detail -> Screen.Home
            is Screen.Auth -> {
                // Màn đăng nhập mở từ lời mời / Cài đặt / phiên hết hạn: Quay lại là về tủ sách như khách,
                // không thoát ứng dụng. Chỉ chuẩn hoá hồ sơ khi còn mang dấu tài khoản cũ (giữ nguyên hồ sơ khách tự chỉnh).
                if (userRepository.profile.value.authProvider != "guest") userRepository.continueAsGuest()
                Screen.Home
            }
            else -> Screen.Home
        }
    }

    AppTheme(themeMode = settingsState.profile.preferredTheme) {
        Crossfade(targetState = currentScreen) { screen ->
            when (screen) {
                is Screen.Auth -> {
                    AuthScreen(
                        viewModel = authViewModel,
                        onAuthSuccess = {
                            homeViewModel.syncCloudProgress()
                            vocabularyRepository?.requestSync(0L) // máy mới / vừa đổi tài khoản: kéo sổ từ về ngay
                            currentScreen = Screen.Home
                        }
                    )
                }

                is Screen.Home -> {
                    HomeScreen(
                        viewModel = homeViewModel,
                        vocabularyViewModel = vocabularyViewModel,
                        settingsViewModel = settingsViewModel,
                        insightsViewModel = insightsViewModel,
                        onSelectNovel = { novel ->
                            detailViewModel.loadNovel(novel)
                            currentScreen = Screen.Detail(novel)
                        },
                        onContinueReading = { novel ->
                            val chNum = if (novel.lastReadChapter > 0) novel.lastReadChapter else 1
                            val targetChapter = Chapter(
                                id = "${novel.id}_${chNum}",
                                novelId = novel.id,
                                chapterNumber = chNum,
                                title = "Chương $chNum",
                                titleVi = "Chương $chNum"
                            )
                            readerViewModel.init(novel, targetChapter, emptyList(), autoStartTts = false)
                            currentScreen = Screen.Reader(novel, targetChapter)
                        },
                        onLogout = {
                            authViewModel.reset()
                            currentScreen = Screen.Auth
                        },
                        miniPlayer = miniPlayerSlot
                    )
                }

                is Screen.Detail -> {
                    androidx.compose.runtime.LaunchedEffect(screen.novel.id) {
                        if (detailViewModel.uiState.value.novel?.id != screen.novel.id) {
                            detailViewModel.loadNovel(screen.novel)
                        }
                    }
                    NovelDetailScreen(
                        novel = screen.novel,
                        viewModel = detailViewModel,
                        onBack = { currentScreen = Screen.Home },
                        onReadChapter = { novel, chapter ->
                            readerViewModel.init(novel, chapter, detailViewModel.uiState.value.chapters, autoStartTts = false)
                            currentScreen = Screen.Reader(novel, chapter)
                        },
                        onStartTts = { novel, chapter ->
                            readerViewModel.init(novel, chapter, detailViewModel.uiState.value.chapters, autoStartTts = true)
                            currentScreen = Screen.Reader(novel, chapter)
                        },
                        miniPlayer = miniPlayerSlot
                    )
                }

                is Screen.Reader -> {
                    androidx.compose.runtime.LaunchedEffect(screen.novel.id, screen.chapter.chapterNumber) {
                        val state = readerViewModel.uiState.value
                        if (state.currentChapter?.chapterNumber != screen.chapter.chapterNumber || state.novel?.id != screen.novel.id) {
                            readerViewModel.init(
                                novel = screen.novel,
                                chapter = screen.chapter,
                                initialChapters = listOf(screen.chapter),
                                autoStartTts = false,
                                initialBilingualMode = screen.initialBilingualMode
                            )
                        }
                    }
                    ReaderScreen(
                        viewModel = readerViewModel,
                        onBack = {
                            readerViewModel.flushReadingProgressImmediately()
                            currentScreen = Screen.Detail(screen.novel)
                        }
                    )
                }
            }
        }

        // Gia hạn token, lời nhắc "phiên hết hạn", hướng dẫn chào mừng và lời mời đăng nhập ở lần chạy đầu
        SessionPrompts(
            userRepository = userRepository,
            homeViewModel = homeViewModel,
            allowExpiryPrompt = currentScreen !is Screen.Reader && currentScreen !is Screen.Auth,
            onNavigateToAuth = {
                authViewModel.reset()
                currentScreen = Screen.Auth
            }
        )
    }
}
