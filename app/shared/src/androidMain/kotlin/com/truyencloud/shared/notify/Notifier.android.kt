package com.truyencloud.shared.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Thông báo trên Android.
 *
 * Quyền POST_NOTIFICATIONS đã được khai báo và hỏi sẵn trong MainActivity từ trước,
 * nhưng không có gì dùng tới — ứng dụng chưa từng gửi thông báo nào.
 */
class AndroidNotifier(private val context: Context) : Notifier {

    override val hasPermission: Boolean
        get() {
            // Trước Android 13 không có quyền riêng cho thông báo, nhưng người dùng
            // vẫn có thể tắt thông báo của ứng dụng trong Cài đặt — phải hỏi cả hai.
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
            return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /**
     * Không tự hỏi quyền được: hộp thoại xin quyền phải do một Activity hiện lên.
     * MainActivity đã hỏi lúc khởi động, nên ở đây chỉ trả về trạng thái hiện tại.
     */
    override suspend fun requestPermission(): Boolean = hasPermission

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Chương mới",
                // DEFAULT chứ không HIGH: truyện ra chương mới không phải việc khẩn,
                // đẩy lên HIGH sẽ bật âm thanh và nổi đè lên màn hình.
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Báo khi truyện bạn đang đọc có chương mới."
            }
        )
    }

    @SuppressLint("MissingPermission")
    override fun notifyNewChapters(notices: List<NewChapterNotice>) {
        if (notices.isEmpty() || !hasPermission) return
        ensureChannel()

        val title: String
        val body: String
        if (notices.size == 1) {
            val n = notices.first()
            title = n.novelTitle
            body = "Có ${n.newChapters} chương mới (tổng ${n.totalChapters})."
        } else {
            val total = notices.sumOf { it.newChapters }
            title = "$total chương mới trong tủ sách"
            body = notices.joinToString(", ") { "${it.novelTitle} (+${it.newChapters})" }
        }

        // Trước đây thông báo KHÔNG có content intent nên chạm vào không làm gì. Một truyện -> mở thẳng truyện đó
        // (qua extra "open_novel_id"); nhiều truyện -> chỉ mở ứng dụng.
        val novelId = notices.singleOrNull()?.novelId
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        openAppPendingIntent(context, requestCode = novelId?.hashCode() ?: NOTIFICATION_ID, novelId = novelId)
            ?.let { builder.setContentIntent(it) }
        val notification = builder.build()

        try {
            // Dùng CÙNG một id: lần báo sau thay lần trước thay vì chồng thêm một
            // thông báo nữa cho cùng một việc.
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Quyền bị rút ngay giữa lúc gửi — bỏ qua, lần sau kiểm tra lại.
        }
    }

    companion object {
        const val CHANNEL_ID = "new_chapters"
        private const val NOTIFICATION_ID = 4801

        /** Extra trên Intent mở ứng dụng: id truyện cần mở. MainActivity đọc khoá này để điều hướng. */
        const val EXTRA_OPEN_NOVEL_ID = "open_novel_id"

        /**
         * PendingIntent mở ứng dụng (launch intent của chính gói này), tuỳ chọn kèm id truyện.
         * Mỗi [requestCode] khác nhau để extra của thông báo này không ghi đè extra của thông báo khác.
         */
        internal fun openAppPendingIntent(context: Context, requestCode: Int, novelId: String?): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!novelId.isNullOrBlank()) launch.putExtra(EXTRA_OPEN_NOVEL_ID, novelId)
            return PendingIntent.getActivity(
                context,
                requestCode,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var appContext: Context? = null

        /** Gọi một lần trong Activity trước khi dùng createNotifier(). */
        fun init(context: Context) {
            appContext = context.applicationContext
        }

        internal fun create(): Notifier {
            val ctx = appContext ?: return NoOpNotifier()
            return AndroidNotifier(ctx)
        }

        internal fun contextOrNull(): Context? = appContext
    }
}

actual fun createNotifier(): Notifier = AndroidNotifier.create()

actual fun applyBackgroundUpdateChecks(enabled: Boolean) {
    val ctx = AndroidNotifier.contextOrNull() ?: return
    NewChapterWorker.apply(ctx, enabled)
}

actual fun backgroundUpdateCheckDescription(): String =
    "Kiểm tra nền khoảng 6 giờ một lần khi có mạng."
