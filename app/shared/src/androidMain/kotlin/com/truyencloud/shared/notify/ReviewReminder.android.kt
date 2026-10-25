package com.truyencloud.shared.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

actual fun scheduleReviewReminder(config: ReviewReminderConfig, dueCount: Int, replaceExisting: Boolean) {
    // Android đếm thẻ đến hạn ngay lúc nổ (trong worker) nên không cần dueCount.
    val ctx = AndroidNotifier.contextOrNull() ?: return
    ReviewReminderWorker.apply(ctx, config, replaceExisting)
}

/** Hiện thông báo nhắc ôn: kênh riêng, chạm vào mở ứng dụng. */
internal object ReviewReminderNotifier {
    const val CHANNEL_ID = "review_reminder"
    private const val NOTIFICATION_ID = 4802

    @SuppressLint("MissingPermission")
    fun show(context: Context, dueCount: Int) {
        // Không có thẻ nào đến hạn thì im lặng: một lời nhắc rỗng chỉ khiến người dùng tắt thông báo.
        if (dueCount <= 0 || !canPost(context)) return
        ensureChannel(context)

        val body = ReviewReminderMath.message(dueCount)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(ReviewReminderMath.TITLE)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        AndroidNotifier.openAppPendingIntent(context, requestCode = NOTIFICATION_ID, novelId = null)
            ?.let { builder.setContentIntent(it) }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Quyền bị rút ngay giữa lúc gửi — bỏ qua, hôm sau kiểm tra lại.
        }
    }

    private fun canPost(context: Context): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nhắc ôn từ vựng", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Nhắc mỗi ngày khi có từ đến hạn ôn."
            }
        )
    }
}
