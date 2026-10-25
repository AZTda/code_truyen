package com.truyencloud.shared.repo

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.getCurrentEpochMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Quản lý đồng bộ vòng đời ứng dụng (Lifecycle Auto-Sync).
 *
 * Khi người dùng chuyển app sang chạy nền (onPause/onStop trên Android, didEnterBackground trên iOS)
 * hoặc thoát khỏi màn hình đọc, hệ thống tự động:
 * 1. Lưu ngay lập tức vị trí cuộn & tiến độ đọc của chương hiện tại (không chờ debounce 3s).
 * 2. Đẩy tiến độ còn chờ & đồng bộ danh sách dấu trang (Bookmarks) lên MiniPC backend.
 * 3. Chạy thêm việc nền do nơi gọi truyền vào (vd. đồng bộ từ vựng).
 *
 * Android gọi [onAppBackground] ở CẢ onPause lẫn onStop (iOS cũng có hai sự kiện tương tự) nên
 * lượt nền được gộp: sự kiện thứ hai trong [BACKGROUND_DEDUP_MS] bị bỏ qua.
 */
object LifecycleSyncManager {
    private var onReaderFlush: (() -> Unit)? = null
    @Volatile
    private var onForegroundSync: (suspend () -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backgroundGate = SyncThrottle(BACKGROUND_DEDUP_MS) { getCurrentEpochMillis() }
    private val foregroundGate = SyncThrottle(FOREGROUND_MIN_INTERVAL_MS) { getCurrentEpochMillis() }

    private val _isForeground = MutableStateFlow(true)

    /**
     * Ứng dụng đang ở nền trước? Trình đọc dùng để không cộng phút đọc khi tắt màn hình / ra màn hình chính.
     * Cập nhật ở đầu [onAppForeground] / [onAppBackground], TRƯỚC mọi nhánh thoát sớm (khách chưa đăng nhập vẫn phải cập nhật).
     */
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private var lastBackgroundEventAt = 0L

    /** Chỉ cho kiểm thử: các bộ giãn cách là trạng thái toàn cục của tiến trình nên bài kiểm thử phải tự đặt lại. */
    internal fun resetThrottles() {
        backgroundGate.reset()
        foregroundGate.reset()
    }

    fun registerReaderFlush(callback: () -> Unit) {
        onReaderFlush = callback
    }

    fun unregisterReaderFlush() {
        onReaderFlush = null
    }

    /**
     * Gắn việc chạy thêm mỗi lần ứng dụng quay lại, CÙNG nhịp giãn cách với tiến độ / dấu trang — nơi gắn đồng bộ từ vựng
     * (`{ vocabularyRepository.syncIfLoggedIn() }`): các nơi gọi [onAppForeground] ở tầng nền tảng không giữ tham chiếu tới
     * kho từ vựng, và trước đây từ vựng chỉ được kéo sau khi có thay đổi cục bộ nên máy thứ hai thấy sổ từ cũ / trống.
     */
    fun registerForegroundSync(task: suspend () -> Unit) {
        onForegroundSync = task
    }

    fun unregisterForegroundSync() {
        onForegroundSync = null
    }

    /**
     * @param vocabularyRepository đồng bộ từ vựng im lặng nếu đã đăng nhập (`syncIfLoggedIn`).
     * @param onBackgroundExtra việc nền thêm bất kỳ chạy sau cùng. Mặc định không làm gì.
     */
    fun onAppBackground(
        readerRepository: ReaderRepository? = null,
        bookmarkRepository: BookmarkRepository? = null,
        vocabularyRepository: VocabularyRepository? = null,
        onBackgroundExtra: suspend () -> Unit = {}
    ) {
        markBackgroundEvent()

        // 1. Lưu ngay tiến độ đọc hiện tại trong bộ nhớ / SQLite
        try {
            onReaderFlush?.invoke()
        } catch (_: Exception) {}

        if (!backgroundGate.shouldRun()) return
        backgroundGate.markStarted()

        // Khách không đồng bộ gì cả — nhưng iOS chụp số thẻ vào thông báo nhắc ôn nên vẫn làm mới nội dung đó.
        if (TruyenCloudApi.authToken.isBlank()) {
            scope.launch { safely { vocabularyRepository?.refreshReminderContent() } }
            return
        }

        // 2. Kích hoạt đồng bộ đám mây cho tiến độ và Dấu trang (Bookmarks)
        scope.launch {
            // onReaderFlush chỉ KHỞI ĐỘNG việc lưu (chạy ở coroutine khác): chờ chút để vị trí cuộn cuối
            // kịp vào Room rồi mới đẩy, nếu không lượt đẩy này mang vị trí cũ.
            delay(READER_FLUSH_GRACE_MS)
            safely { readerRepository?.flushDirtyProgress() }
            safely { bookmarkRepository?.syncWithServer(force = true) }
            safely { vocabularyRepository?.syncIfLoggedIn() }
            safely { onBackgroundExtra() }
        }
    }

    /**
     * Ứng dụng quay lại: thử lại các thay đổi tiến độ còn dirty (đẩy hụt lúc trước) và đồng bộ
     * dấu trang (đã giãn cách ~30 giây nên gọi dày đặc cũng không tốn).
     */
    fun onAppForeground(
        readerRepository: ReaderRepository? = null,
        bookmarkRepository: BookmarkRepository? = null,
        vocabularyRepository: VocabularyRepository? = null
    ) {
        lastBackgroundEventAt = 0L
        _isForeground.value = true

        if (TruyenCloudApi.authToken.isBlank()) return
        if (!foregroundGate.shouldRun()) return
        foregroundGate.markStarted()
        val extra = onForegroundSync
        scope.launch {
            safely { readerRepository?.flushDirtyProgress() }
            safely { bookmarkRepository?.syncWithServer(force = false) }
            safely { vocabularyRepository?.syncIfLoggedIn() }
            safely { extra?.invoke() }
        }
    }

    /**
     * Hai sự kiện nền liên tiếp (onPause + onStop / WillResignActive + DidEnterBackground) mới là ra nền thật. Một sự kiện
     * lẻ (hộp thoại xin quyền, đa cửa sổ, Control Center) thì ứng dụng vẫn hiện trên màn hình và Android KHÔNG gọi
     * [onAppForeground] khi quay lại (chỉ ở onStart), nên không hạ cờ kẻo kẹt ở "đang ở nền sau".
     */
    private fun markBackgroundEvent() {
        val now = getCurrentEpochMillis()
        if (now - lastBackgroundEventAt <= BACKGROUND_PAIR_MS) _isForeground.value = false
        lastBackgroundEventAt = now
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Đồng bộ nền hỏng không được làm sập hay chặn các bước sau; trạng thái lỗi đã do từng kho báo vào SyncStatus.
        }
    }

    private const val BACKGROUND_PAIR_MS = 5_000L
    private const val BACKGROUND_DEDUP_MS = 10_000L
    private const val FOREGROUND_MIN_INTERVAL_MS = 15_000L
    private const val READER_FLUSH_GRACE_MS = 400L
}
