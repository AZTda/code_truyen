package com.truyencloud.shared.notify

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.truyencloud.shared.data.LocalCache
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.prefs.SettingsStore
import com.truyencloud.shared.prefs.createSettingsStore
import java.util.concurrent.TimeUnit

/**
 * Nhắc ôn từ vựng hằng ngày. Chạy trong process có thể MỚI HOÀN TOÀN (MainActivity chưa từng chạy) nên tự dựng
 * kho cài đặt và mở database như [NewChapterWorker]. Số thẻ đến hạn được đếm NGAY lúc nổ, không phải lúc đặt lịch.
 */
class ReviewReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val ctx = applicationContext
            SettingsStore.init(ctx)
            val config = ReviewReminderPrefs.load(createSettingsStore())
            if (!config.enabled) return Result.success()

            val db = Room.databaseBuilder(ctx, LocalCache::class.java, DB_NAME)
                .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6)
                .build()
            val due = try {
                db.savedWordDao().countDueOnce(getCurrentEpochMillis())
            } finally {
                db.close()
            }
            ReviewReminderNotifier.show(ctx, due)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Nhac on tu vung that bai: ${e.message}")
            // Lần sau (24 giờ nữa) sẽ thử lại; retry() ở đây có thể bắn thông báo muộn hàng giờ.
            Result.success()
        }
    }

    companion object {
        private const val TAG = "ReviewReminderWorker"
        private const val WORK_NAME = "truyencloud_review_reminder"

        /** Phải khớp tên database trong MainActivity, nếu không worker sẽ tạo file rỗng thứ hai. */
        private const val DB_NAME = "truyencloud_cache.db"

        fun apply(context: Context, config: ReviewReminderConfig, replaceExisting: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!config.enabled) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val delayMs = (ReviewReminderMath.nextFireMillis(getCurrentEpochMillis(), config.hour, config.minute) -
                getCurrentEpochMillis()).coerceAtLeast(0L)
            val request = PeriodicWorkRequestBuilder<ReviewReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP khi chỉ đảm bảo lịch còn tồn tại (mỗi lần mở app): REPLACE sẽ đẩy lùi giờ nổ mãi mãi.
                if (replaceExisting) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
