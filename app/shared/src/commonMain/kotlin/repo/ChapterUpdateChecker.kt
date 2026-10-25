package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ShelfNovelState
import com.truyencloud.shared.api.UpdatesApi
import com.truyencloud.shared.data.NovelDao
import com.truyencloud.shared.data.displayTitle
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.notify.NewChapterNotice
import com.truyencloud.shared.notify.Notifier
import com.truyencloud.shared.notify.applyBackgroundUpdateChecks
import com.truyencloud.shared.prefs.PrefKeys
import com.truyencloud.shared.prefs.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Tìm chương mới của các truyện đang đọc và báo cho người dùng.
 *
 * Máy chủ vốn đã quét chương mới ngầm (`auto_updater`) và có endpoint trạng thái,
 * nhưng ứng dụng chưa từng gọi tới, và trong toàn bộ mã nguồn không có một dòng
 * thông báo nào. Người đọc chỉ biết truyện ra chương mới khi tự mở từng truyện ra bấm.
 */
class ChapterUpdateChecker(
    private val novelDao: NovelDao,
    private val notifier: Notifier,
    private val store: SettingsStore,
    private val updatesApi: UpdatesApi = UpdatesApi()
) {
    companion object {
        /** Khớp với giới hạn phía máy chủ của `/novels/updates/check-batch`. */
        const val MAX_NOVELS_PER_CHECK = 100

        /**
         * Khoảng cách tối thiểu giữa hai lần kiểm tra.
         *
         * Tiến trình nền có thể bị đánh thức nhiều lần hơn chu kỳ đã đặt (người dùng
         * mở lại ứng dụng, hệ thống gom việc). Không chặn thì mỗi lần thức dậy là một
         * lượt gọi mạng, tốn pin mà không có gì mới để báo.
         */
        const val MIN_INTERVAL_MS = 3L * 60 * 60 * 1000 // 3 giờ
    }

    private val _enabled = MutableStateFlow(store.getBoolean(PrefKeys.NOTIFY_NEW_CHAPTERS, false))

    /** Theo dõi được trạng thái bật/tắt (vd. nút "Bật thông báo" trong trình đọc tự đổi nhãn). */
    val enabledFlow: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * Thông báo chương mới đang bật? MẶC ĐỊNH TẮT — người dùng phải chủ động bật.
     * Đặt giá trị này chỉ ghi cờ; muốn bật đầy đủ (xin quyền thông báo + lên lịch kiểm tra nền)
     * thì dùng [enableWithPermission], muốn tắt đầy đủ thì [disable].
     */
    var isEnabled: Boolean
        get() = store.getBoolean(PrefKeys.NOTIFY_NEW_CHAPTERS, false)
        set(value) {
            store.putBoolean(PrefKeys.NOTIFY_NEW_CHAPTERS, value)
            _enabled.value = value
        }

    /**
     * Bật thông báo chương mới: xin quyền thông báo nếu chưa có, đặt cờ, rồi lên lịch kiểm tra
     * nền (Android: WorkManager; iOS: kiểm tra lúc mở lại ứng dụng).
     *
     * @return false nếu người dùng từ chối quyền (cờ giữ nguyên là tắt).
     */
    suspend fun enableWithPermission(): Boolean {
        val granted = notifier.hasPermission || notifier.requestPermission()
        if (!granted) return false
        isEnabled = true
        applyBackgroundUpdateChecks(true)
        return true
    }

    /** Tắt thông báo và huỷ lịch kiểm tra nền — từ đây [runCheck] thoát ngay, không tốn mạng hay DB. */
    fun disable() {
        isEnabled = false
        applyBackgroundUpdateChecks(false)
    }

    private fun notifiedKey(novelId: String) = PrefKeys.NOTIFIED_CHAPTERS_PREFIX + novelId

    /**
     * Chạy một lượt kiểm tra.
     *
     * @param force bỏ qua khoảng cách tối thiểu (người dùng tự bấm kiểm tra).
     * @return số truyện đã báo; 0 nghĩa là không có gì mới hoặc bị bỏ qua.
     */
    suspend fun runCheck(force: Boolean = false): Int {
        if (!isEnabled && !force) return 0

        val now = getCurrentEpochMillis()
        val last = store.getLong(PrefKeys.LAST_UPDATE_CHECK_AT, 0L)
        if (!force && last > 0 && now - last < MIN_INTERVAL_MS) return 0

        val shelf = try {
            novelDao.getAllNovels().first()
        } catch (e: Exception) {
            e.printStackTrace()
            return 0
        }

        // Mọi truyện có hàng trong bảng novels đều là truyện người dùng CHỦ ĐỘNG đưa vào tủ (thêm vào
        // tủ, yêu thích hoặc đã đọc): mở trang chi tiết không còn tự chèn hàng nên không cần lọc thêm
        // để tránh báo chương mới của truyện chỉ lướt qua một lần.
        val watched = shelf.take(MAX_NOVELS_PER_CHECK)
        if (watched.isEmpty()) {
            store.putLong(PrefKeys.LAST_UPDATE_CHECK_AT, now)
            return 0
        }

        val response = try {
            updatesApi.checkBatch(
                watched.map { ShelfNovelState(novel_id = it.id, known_chapters = it.totalChapters) }
            )
        } catch (e: Exception) {
            // Mất mạng hoặc máy chủ nghỉ: im lặng, lần sau kiểm tra lại.
            return 0
        }

        store.putLong(PrefKeys.LAST_UPDATE_CHECK_AT, now)
        if (response.updates.isEmpty()) return 0

        val novelById = watched.associateBy { it.id }
        val notices = response.updates.mapNotNull { update ->
            val novel = novelById[update.novel_id]
            if (novel != null && update.total_chapters > novel.totalChapters) {
                runCatching {
                    val fresh = novelDao.getNovelById(novel.id) ?: novel
                    if (update.total_chapters > fresh.totalChapters) {
                        novelDao.upsertNovel(fresh.copy(totalChapters = update.total_chapters))
                    }
                }
            }
            // Đã báo đúng số chương này rồi thì thôi — nếu không, mỗi lần tiến trình
            // nền thức dậy người dùng lại nhận đúng một thông báo cũ.
            val alreadyNotified = store.getInt(notifiedKey(update.novel_id), 0)
            if (update.total_chapters <= alreadyNotified) return@mapNotNull null
            NewChapterNotice(
                novelId = update.novel_id,
                novelTitle = novel?.displayTitle ?: update.displayTitle,
                newChapters = update.new_chapters,
                totalChapters = update.total_chapters
            )
        }
        if (notices.isEmpty()) return 0

        notifier.notifyNewChapters(notices)
        notices.forEach { store.putInt(notifiedKey(it.novelId), it.totalChapters) }
        return notices.size
    }
}
