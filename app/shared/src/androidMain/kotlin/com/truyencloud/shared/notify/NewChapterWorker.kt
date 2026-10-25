package com.truyencloud.shared.notify

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.LocalCache
import com.truyencloud.shared.prefs.PrefKeys
import com.truyencloud.shared.prefs.SettingsStore
import com.truyencloud.shared.prefs.createSettingsStore
import com.truyencloud.shared.repo.ChapterUpdateChecker
import java.util.concurrent.TimeUnit

/**
 * Kiểm tra chương mới khi ứng dụng KHÔNG mở.
 *
 * Điểm dễ hỏng: tiến trình này có thể chạy trong một process hoàn toàn mới, nơi
 * `MainActivity` chưa từng chạy. Nên mọi thứ MainActivity vẫn đặt sẵn —
 * `SettingsStore.init`, `AndroidNotifier.init`, `TruyenCloudApi.baseUrl`,
 * `TruyenCloudApi.authToken` — ở đây đều chưa có. Worker phải tự dựng lấy,
 * nếu không nó sẽ lặng lẽ gọi sai địa chỉ máy chủ rồi thất bại mãi mãi.
 */
class NewChapterWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val ctx = applicationContext

            SettingsStore.init(ctx)
            val store = createSettingsStore()

            if (!store.getBoolean(PrefKeys.NOTIFY_NEW_CHAPTERS, false)) {
                return Result.success()
            }

            // Khôi phục trạng thái tĩnh mà MainActivity vẫn đặt hộ.
            store.getString(PrefKeys.SERVER_URL, "").takeIf { it.isNotBlank() }?.let {
                TruyenCloudApi.baseUrl = it
            }
            TruyenCloudApi.authToken = store.getSecret(PrefKeys.AUTH_TOKEN)

            val db = Room.databaseBuilder(ctx, LocalCache::class.java, DB_NAME)
                .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6)
                .build()

            val checker = ChapterUpdateChecker(
                novelDao = db.novelDao(),
                notifier = AndroidNotifier(ctx),
                store = store
            )
            val notified = checker.runCheck()
            db.close()

            Log.i(TAG, "Kiem tra chuong moi xong, da bao $notified truyen.")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Kiem tra chuong moi that bai: ${e.message}")
            // retry() chứ không failure(): mất mạng tạm thời không nên tắt hẳn
            // việc kiểm tra định kỳ.
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NewChapterWorker"
        private const val WORK_NAME = "truyencloud_new_chapter_check"

        /** Phải khớp tên database trong MainActivity, nếu không worker sẽ tạo file rỗng thứ hai. */
        private const val DB_NAME = "truyencloud_cache.db"

        /**
         * 6 giờ một lần. Truyện mạng thường ra 1–3 chương/ngày nên kiểm tra dày hơn
         * chỉ tốn pin; WorkManager cũng tự gom việc nên chu kỳ thực tế có thể dài hơn.
         */
        private const val REPEAT_HOURS = 6L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewChapterWorker>(REPEAT_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP: lần mở ứng dụng nào cũng gọi hàm này, dùng UPDATE hay REPLACE
                // sẽ đẩy lùi lần chạy kế tiếp mãi và việc kiểm tra không bao giờ tới.
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** Bật hoặc tắt theo cài đặt của người dùng. */
        fun apply(context: Context, enabled: Boolean) {
            if (enabled) schedule(context) else cancel(context)
        }
    }
}
