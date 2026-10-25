package com.truyencloud.compose

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import androidx.room.Room
import androidx.sqlite.driver.NativeSQLiteDriver
import com.truyencloud.compose.navigation.AppNav
import com.truyencloud.shared.api.ChapterApi
import com.truyencloud.shared.api.CollocationItem
import com.truyencloud.shared.api.EnglishApi
import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.api.ProgressApi
import com.truyencloud.shared.api.TranslateApi
import com.truyencloud.shared.data.LocalCache
import com.truyencloud.shared.data.LocalDataMaintenance
import com.truyencloud.shared.notify.createNotifier
import com.truyencloud.shared.prefs.PrefKeys
import com.truyencloud.shared.prefs.createSettingsStore
import com.truyencloud.shared.repo.*
import com.truyencloud.shared.tts.IosTtsSpeaker
import com.truyencloud.shared.tts.applyPlaybackSettings
import com.truyencloud.shared.tts.ttsPlaybackSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIViewController

/**
 * Giữ một thể hiện duy nhất của cơ sở dữ liệu Room và các Repository cho toàn tiến trình iOS:
 * tránh việc tái tổ hợp (recomposition) hoặc SwiftUI dựng lại UIViewController tạo lặp kết nối SQLite,
 * nhân bản observer NSNotificationCenter và phóng các coroutine khởi động nhiều lần.
 */
private object IosAppContainer {
    val documentDirectory: String = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true
    ).firstOrNull() as? String ?: NSHomeDirectory()
    val dbFilePath: String = "$documentDirectory/truyencloud_cache.db"

    val db: LocalCache = Room.databaseBuilder<LocalCache>(name = dbFilePath)
        .setDriver(NativeSQLiteDriver())
        .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6)
        .build()

    val novelApi = NovelApi()
    val chapterApi = ChapterApi()
    val translateApi = TranslateApi()
    val progressApi = ProgressApi()
    val englishApi = EnglishApi()

    val novelRepo = NovelRepository(
        novelApi,
        db.novelDao(),
        chapterDao = db.chapterDao(),
        bookmarkDao = db.bookmarkDao(),
        syncStateDao = db.syncStateDao()
    )
    val chapterRepo = ChapterRepository(chapterApi, db.chapterDao())
    val termMemoryRepo = TermMemoryRepository(db.termMemoryDao())
    val translateRepo = TranslateRepository(translateApi, db.chapterDao(), termMemoryRepo)
    val bookmarkRepo = BookmarkRepository(db.bookmarkDao(), syncStateDao = db.syncStateDao())
    val readerRepo = ReaderRepository(
        db.novelDao(),
        db.chapterDao(),
        db.readingSessionDao(),
        progressApi,
        syncStateDao = db.syncStateDao(),
        onFullSync = { bookmarkRepo.syncWithServer() }
    )
    val settingsStore = createSettingsStore()
    val dataMaintenance = LocalDataMaintenance(db, dbFilePath)
    val userRepo = UserRepository(store = settingsStore, dataWiper = dataMaintenance)
    val vocabRepo = VocabularyRepository(
        englishApi = englishApi,
        savedWordDao = db.savedWordDao(),
        reviewLogDao = db.reviewLogDao()
    )
    val updateChecker = ChapterUpdateChecker(
        novelDao = db.novelDao(),
        notifier = createNotifier(),
        store = settingsStore
    )
    val ttsSpeaker = IosTtsSpeaker.obtain()

    var startupTasksLaunched = false
}

fun MainViewController(): UIViewController = ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) {
    val container = remember {
        IosAppContainer.also {
            it.ttsSpeaker.applyPlaybackSettings(it.userRepo.profile.value.ttsPlaybackSettings())
        }
    }

    DisposableEffect(container) {
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        val bgObserver = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = queue
        ) { _ ->
            LifecycleSyncManager.onAppBackground(
                readerRepository = container.readerRepo,
                bookmarkRepository = container.bookmarkRepo,
                vocabularyRepository = container.vocabRepo
            )
        }
        val fgObserver = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = queue
        ) { _ ->
            LifecycleSyncManager.onAppForeground(
                readerRepository = container.readerRepo,
                bookmarkRepository = container.bookmarkRepo,
                vocabularyRepository = container.vocabRepo
            )
        }
        val resignObserver = center.addObserverForName(
            name = UIApplicationWillResignActiveNotification,
            `object` = null,
            queue = queue
        ) { _ ->
            LifecycleSyncManager.onAppBackground(
                readerRepository = container.readerRepo,
                bookmarkRepository = container.bookmarkRepo,
                vocabularyRepository = container.vocabRepo
            )
        }
        onDispose {
            center.removeObserver(bgObserver)
            center.removeObserver(fgObserver)
            center.removeObserver(resignObserver)
        }
    }

    LaunchedEffect(container) {
        if (container.startupTasksLaunched) return@LaunchedEffect
        container.startupTasksLaunched = true
        withContext(Dispatchers.Default) {
            try { container.updateChecker.runCheck() } catch (_: Exception) {}
            try { container.vocabRepo.migrateLegacyWords() } catch (_: Exception) {}
            try { RemovedContentCleanup.forDatabase(container.db, container.settingsStore).run() } catch (_: Exception) {}

            val seedSamples = !container.settingsStore.getBoolean(PrefKeys.SAMPLE_CONTENT_SEEDED, false)
            if (seedSamples) {
                container.settingsStore.putBoolean(PrefKeys.SAMPLE_CONTENT_SEEDED, true)
                if (container.db.novelDao().countNovels() == 0) container.userRepo.updateReaderPaginated(true)
                val sampleWord = container.db.savedWordDao().getWord("serendipity")
                if (sampleWord == null) {
                    container.vocabRepo.saveWord(
                        word = "serendipity",
                        ipaUs = "/ˌser.ənˈdɪp.ə.t̬i/",
                        ipaUk = "/ˌser.ənˈdɪp.ə.ti/",
                        partOfSpeech = "noun",
                        cefrLevel = "C2",
                        contextMeaningVi = "Sự tình cờ may mắn; năng lực tình cờ phát hiện ra những điều tốt đẹp và quý giá",
                        englishDefinition = "The occurrence and development of events by chance in a happy or beneficial way",
                        contextSentence = "Their meeting was a pure serendipity that forever altered their trajectories.",
                        bookTitle = "Literary Studies",
                        etymology = "Coined by Horace Walpole in 1754 from the Persian fairy tale 'The Three Princes of Serendip'.",
                        collocations = listOf(CollocationItem("pure serendipity"), CollocationItem("happy serendipity"), CollocationItem("stroke of serendipity"))
                    )
                    container.vocabRepo.saveWord(
                        word = "ephemeral",
                        ipaUs = "/ɪˈfem.ɚ.əl/",
                        ipaUk = "/ɪˈfem.ər.əl/",
                        partOfSpeech = "adjective",
                        cefrLevel = "C1",
                        contextMeaningVi = "Phù du, ngắn ngủi, chóng tàn",
                        englishDefinition = "Lasting for a very short time; transitory",
                        contextSentence = "Youth and fleeting autumn blooms are beautifully ephemeral.",
                        bookTitle = "Literary Studies",
                        etymology = "From Greek 'ephemeros', epi (upon) + hemera (day), literally living only a day.",
                        collocations = listOf(CollocationItem("ephemeral nature"), CollocationItem("ephemeral pleasures"), CollocationItem("ephemeral beauty"))
                    )
                }
            }
        }
    }

    AppNav(
        novelRepository = container.novelRepo,
        chapterRepository = container.chapterRepo,
        translateRepository = container.translateRepo,
        readerRepository = container.readerRepo,
        userRepository = container.userRepo,
        novelApi = container.novelApi,
        vocabularyRepository = container.vocabRepo,
        termMemoryRepository = container.termMemoryRepo,
        bookmarkRepository = container.bookmarkRepo,
        savedWordDao = container.db.savedWordDao(),
        readingSessionDao = container.db.readingSessionDao(),
        reviewLogDao = container.db.reviewLogDao(),
        updateChecker = container.updateChecker,
        dataMaintenance = container.dataMaintenance,
        ttsSpeaker = container.ttsSpeaker,
        initialScreen = null,
        initialTab = com.truyencloud.compose.components.NavTab.BOOKSHELF
    )
}


