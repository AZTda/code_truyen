package com.truyencloud.shared.repo

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.notify.ReviewReminderConfig
import com.truyencloud.shared.notify.ReviewReminderPrefs
import com.truyencloud.shared.notify.createNotifier
import com.truyencloud.shared.notify.scheduleReviewReminder
import com.truyencloud.shared.prefs.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Nhắc ôn hằng ngày: đọc / ghi cấu hình và đặt lịch thông báo của nền tảng. */
class VocabularyReminderService(
    private val savedWordDao: SavedWordDao,
    private val settingsStore: SettingsStore?
) {
    fun config(): ReviewReminderConfig =
        settingsStore?.let { ReviewReminderPrefs.load(it) } ?: ReviewReminderConfig()

    /** Chạy lúc mở ứng dụng: đảm bảo lịch còn tồn tại (Android giữ nguyên lịch cũ, iOS chụp lại số thẻ đến hạn). */
    suspend fun ensureScheduled() {
        val cfg = config()
        scheduleReviewReminder(cfg, dueNow(), replaceExisting = false)
    }

    /** @return false nếu bật nhắc nhưng hệ điều hành chưa cho phép hiện thông báo. */
    suspend fun set(config: ReviewReminderConfig): Boolean {
        settingsStore?.let { ReviewReminderPrefs.save(it, config) }
        var allowed = true
        if (config.enabled) {
            val notifier = createNotifier()
            allowed = notifier.hasPermission || notifier.requestPermission()
        }
        scheduleReviewReminder(config, dueNow(), replaceExisting = true)
        return allowed
    }

    /** iOS chụp số thẻ vào nội dung thông báo lúc đặt lịch — đặt lại sau mỗi phiên ôn cho đỡ cũ. */
    suspend fun refreshContent() {
        if (config().enabled) ensureScheduled()
    }

    private suspend fun dueNow(): Int = savedWordDao.countDueOnce(getCurrentEpochMillis())
}

/**
 * Việc nền sau mỗi đợt thay đổi từ vựng / khi ứng dụng ra nền: đồng bộ (CHỈ khi đã đăng nhập) rồi làm mới nội dung
 * nhắc ôn. iOS chụp "N từ cần ôn" vào thông báo lúc đặt lịch nên khách chưa đăng nhập cũng phải được làm mới — bản cũ
 * chỉ làm mới sau khi đồng bộ, tức là khách giữ mãi con số lúc mở ứng dụng.
 */
internal class VocabularyAutoSync(
    private val sync: suspend () -> Unit,
    private val refreshReminder: suspend () -> Unit,
    private val isLoggedIn: () -> Boolean = { TruyenCloudApi.authToken.isNotBlank() }
) {
    suspend fun run() {
        if (isLoggedIn()) sync()
        refreshReminderOnly()
    }

    suspend fun refreshReminderOnly() {
        try {
            refreshReminder()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nhắc ôn chỉ là tiện ích; hệ điều hành từ chối đặt lịch không được làm hỏng đồng bộ.
        }
    }
}

/**
 * Hẹn chạy [syncNow] sau [request]: nhiều lần gọi liên tiếp (một phiên ôn) gộp thành MỘT lần sau lượt cuối cùng.
 * Khách cũng được hẹn — [syncNow] tự bỏ qua phần mạng, chỉ còn làm mới nhắc ôn (xem [VocabularyAutoSync]).
 */
internal class VocabularySyncScheduler(
    private val scope: CoroutineScope,
    private val syncNow: suspend () -> Unit
) {
    private var pending: Job? = null

    fun request(delayMs: Long) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            try {
                syncNow()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}
