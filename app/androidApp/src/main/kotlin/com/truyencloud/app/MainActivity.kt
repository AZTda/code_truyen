package com.truyencloud.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.room.Room
import com.truyencloud.compose.navigation.AppNav
import com.truyencloud.compose.navigation.NavigationRequests
import com.truyencloud.shared.notify.AndroidNotifier
import com.truyencloud.compose.platform.ReaderHardwareKeys
import com.truyencloud.shared.api.ChapterApi
import com.truyencloud.shared.api.CollocationItem
import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.api.TranslateApi
import com.truyencloud.shared.data.LocalCache
import com.truyencloud.shared.data.LocalDataMaintenance
import com.truyencloud.shared.repo.*
import com.truyencloud.shared.tts.applyPlaybackSettings
import com.truyencloud.shared.tts.ttsPlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Bấm thông báo chương mới khi app đang chạy (launchMode singleTop) → mở truyện đó. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenNovelIntent(intent)
    }

    private fun handleOpenNovelIntent(intent: Intent?) {
        intent?.getStringExtra(AndroidNotifier.EXTRA_OPEN_NOVEL_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { NavigationRequests.openNovel(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOpenNovelIntent(intent)

        // Request POST_NOTIFICATIONS on Android 13+ (API 33+) for lockscreen / background media controls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val db = Room.databaseBuilder(
            applicationContext,
            LocalCache::class.java,
            "truyencloud_cache.db"
        )
            // KHONG dung fallbackToDestructiveMigration() tran lan: no XOA SACH database
            // moi lan tang version, keo theo ca bo tu vung, lich su on tap FSRS va cac
            // chuong da tai ve. Cac ban 7->8->9 deu co AutoMigration nen giu duoc du lieu;
            // chi cac ban thu nghiem 1-6 (khong con ai dung) moi phai dung tam nay.
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6)
            .build()

        val novelApi = NovelApi()
        val chapterApi = ChapterApi()
        val translateApi = TranslateApi()

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
        // ProgressApi truoc day KHONG duoc tao o bat ky dau, nen progressApi luon null
        // va ca hai chieu dong bo tien do deu tra ve ngay tu dong dau — endpoint
        // /progress/sync va bang reading_progress ton tai nhung chua tung chay.
        val progressApi = com.truyencloud.shared.api.ProgressApi()
        val bookmarkRepo = BookmarkRepository(db.bookmarkDao(), syncStateDao = db.syncStateDao())
        val readerRepo = ReaderRepository(
            db.novelDao(),
            db.chapterDao(),
            db.readingSessionDao(),
            progressApi,
            syncStateDao = db.syncStateDao(),
            // Dong bo dau trang cung nhip voi dong bo tien do (mo app, mo tu sach, vua dang nhap).
            onFullSync = { bookmarkRepo.syncWithServer() }
        )
        this.readerRepoRef = readerRepo
        this.bookmarkRepoRef = bookmarkRepo
        // Khoi tao kho luu cai dat BEN VUNG truoc khi tao UserRepository.
        // Truoc ban va nay, moi cai dat (chu de, co chu, toc do doc, giong doc,
        // URL may chu) chi nam trong RAM va reset moi lan mo lai ung dung.
        com.truyencloud.shared.prefs.SettingsStore.init(applicationContext)
        com.truyencloud.shared.export.PlatformShareHandler.init(applicationContext)
        com.truyencloud.shared.update.AppUpdateInstaller.init(applicationContext)
        val settingsStore = com.truyencloud.shared.prefs.createSettingsStore()
        // Xoá dữ liệu người dùng khi đổi tài khoản / khi chọn "Xóa dữ liệu trên máy", và VACUUM sau khi dọn chương
        val dataMaintenance = LocalDataMaintenance(db, getDatabasePath("truyencloud_cache.db").absolutePath)
        val userRepo = UserRepository(store = settingsStore, dataWiper = dataMaintenance)

        // Bao chuong moi. Quyen POST_NOTIFICATIONS da duoc khai bao va hoi tu truoc
        // nhung khong co gi dung toi — ung dung chua tung gui thong bao nao.
        com.truyencloud.shared.notify.AndroidNotifier.init(applicationContext)
        val updateChecker = com.truyencloud.shared.repo.ChapterUpdateChecker(
            novelDao = db.novelDao(),
            notifier = com.truyencloud.shared.notify.AndroidNotifier(applicationContext),
            store = settingsStore
        )
        com.truyencloud.shared.notify.NewChapterWorker.apply(
            applicationContext,
            updateChecker.isEnabled
        )
        val englishApi = com.truyencloud.shared.api.EnglishApi()
        val vocabRepo = VocabularyRepository(
            englishApi = englishApi,
            savedWordDao = db.savedWordDao(),
            // Bat buoc co: khong ghi nhat ky on tap thi khong tinh duoc chuoi ngay,
            // ti le nho, va sau nay khong hieu chinh duoc trong so FSRS.
            reviewLogDao = db.reviewLogDao()
        )

        this.vocabRepoRef = vocabRepo

        // Chuyen cac the con theo thuat toan cu sang FSRS (chay mot lan, khong reset tien do)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val migrated = vocabRepo.migrateLegacyWords()
                if (migrated > 0) {
                    android.util.Log.i("TruyenCloud", "Da chuyen $migrated the tu vung sang FSRS.")
                }
            } catch (e: Exception) {
                android.util.Log.w("TruyenCloud", "Loi di tru FSRS: ${e.message}")
            }
        }
        // Don ban sao tren may cua noi dung may chu da go han (nguon tra phi jjwxc, truyen mau Boston); chay moi lan mo app
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val removed = com.truyencloud.shared.repo.RemovedContentCleanup.forDatabase(db, settingsStore).run()
                if (removed.total > 0) android.util.Log.i("TruyenCloud", "Da don noi dung da go: $removed")
            } catch (e: Exception) {
                android.util.Log.w("TruyenCloud", "Loi don noi dung da go: ${e.message}")
            }
        }
        // Bo doc la MOT doi tuong cho ca tien trinh: Activity bi huy/tao lai (hoac vuot khoi
        // da nhiem trong luc dang doc nen) khong lam dut phien doc. Cac setter bo qua gia tri
        // khong doi nen goi lai an toan, khong doc lai cau dang doc.
        val ttsSpeaker = com.truyencloud.shared.tts.AndroidTtsSpeaker.obtain(applicationContext)
        ttsSpeaker.applyPlaybackSettings(userRepo.profile.value.ttsPlaybackSettings())

        // Noi dung mau chi gieo MOT lan (lan chay dau). Truoc day gieo lai moi lan mo app nen truyen/tu mau da xoa
        // (hoac vua bi xoa khi doi tai khoan) cu hien lai.
        val seedSamples = !settingsStore.getBoolean(com.truyencloud.shared.prefs.PrefKeys.SAMPLE_CONTENT_SEEDED, false)
        if (seedSamples) settingsStore.putBoolean(com.truyencloud.shared.prefs.PrefKeys.SAMPLE_CONTENT_SEEDED, true)
        if (seedSamples) CoroutineScope(Dispatchers.IO).launch {
            // Truyen mau Boston da bo (16/10/2026, nguon jjwxc da go): chi con tu vung mau.
            // Da BO phan nap san 7 ngay doc gia o day.
            // ReaderViewModel goi recordReadingSession() that su khi nguoi dung doc,
            // nen du lieu gia chi lam ban bieu do nhiet va chuoi ngay doc lien tiep.

            // Preseed initial academic English vocabulary words for PhD learning deck
            val sampleWord = db.savedWordDao().getWord("serendipity")
            if (sampleWord == null) {
                vocabRepo.saveWord(
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
                vocabRepo.saveWord(
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

        setContent {
            // Biểu tượng thanh trạng thái đọc được trên cả nền sáng lẫn tối của chủ đề đang chọn
            SystemBarsEffect(userRepo.profile.collectAsState().value.preferredTheme)
            AppNav(
                novelRepository = novelRepo,
                chapterRepository = chapterRepo,
                translateRepository = translateRepo,
                readerRepository = readerRepo,
                userRepository = userRepo,
                novelApi = novelApi,
                vocabularyRepository = vocabRepo,
                termMemoryRepository = termMemoryRepo,
                bookmarkRepository = bookmarkRepo,
                savedWordDao = db.savedWordDao(),
                readingSessionDao = db.readingSessionDao(),
                reviewLogDao = db.reviewLogDao(),
                updateChecker = updateChecker,
                dataMaintenance = dataMaintenance,
                ttsSpeaker = ttsSpeaker,
                initialScreen = if (
                    userRepo.profile.value.isLoggedIn || userRepo.profile.value.authProvider == "guest"
                ) com.truyencloud.compose.navigation.Screen.Home else com.truyencloud.compose.navigation.Screen.Auth
            )
        }
    }

    private var readerRepoRef: ReaderRepository? = null
    private var bookmarkRepoRef: BookmarkRepository? = null
    private var vocabRepoRef: VocabularyRepository? = null

    /**
     * Phím âm lượng lật trang khi màn đọc bật tuỳ chọn: chuyển sự kiện sang [ReaderHardwareKeys] ở lần NHẤN
     * (ACTION_DOWN, không tính lặp do giữ phím) và nuốt phím để âm lượng không đổi. Khi màn đọc không bật thì
     * trả lại cho hệ thống xử lý âm lượng như bình thường.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val consumed = when {
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 ->
                    ReaderHardwareKeys.onVolumeKey(isVolumeDown = code == KeyEvent.KEYCODE_VOLUME_DOWN)
                // Giữ phím (lặp) và nhả phím cũng phải nuốt, nếu không hệ thống vẫn chỉnh âm lượng / phát tiếng
                else -> ReaderHardwareKeys.volumeKeyPagingEnabled
            }
            if (consumed) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        // Quay lại ứng dụng: thử lại các thay đổi tiến độ đẩy hụt lúc trước và đồng bộ dấu trang.
        com.truyencloud.shared.repo.LifecycleSyncManager.onAppForeground(
            readerRepository = readerRepoRef,
            bookmarkRepository = bookmarkRepoRef
        )
    }

    override fun onPause() {
        super.onPause()
        com.truyencloud.shared.repo.LifecycleSyncManager.onAppBackground(
            readerRepository = readerRepoRef,
            bookmarkRepository = bookmarkRepoRef,
            vocabularyRepository = vocabRepoRef
        )
    }

    override fun onStop() {
        super.onStop()
        com.truyencloud.shared.repo.LifecycleSyncManager.onAppBackground(
            readerRepository = readerRepoRef,
            bookmarkRepository = bookmarkRepoRef,
            vocabularyRepository = vocabRepoRef
        )
    }

    override fun onDestroy() {
        // Chi giai phong bo may doc khi KHONG con doc nen: doi cau hinh hoac dang phat nen
        // (co dich vu foreground giu tien trinh) thi phien doc phai song tiep.
        if (!isChangingConfigurations) {
            try { com.truyencloud.shared.tts.AndroidTtsSpeaker.releaseIfIdle() } catch (_: Exception) {}
        }
        readerRepoRef = null
        bookmarkRepoRef = null
        vocabRepoRef = null
        super.onDestroy()
    }
}
