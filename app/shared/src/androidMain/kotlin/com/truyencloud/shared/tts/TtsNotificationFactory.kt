package com.truyencloud.shared.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession

/** Dựng thông báo điều khiển phát cho TtsPlaybackService. */
internal object TtsNotificationFactory {
    const val CHANNEL_ID = "truyencloud_tts_playback_channel"
    private const val CHANNEL_NAME = "Giọng đọc Truyện Cloud (TTS)"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = "Kênh phát âm thanh đọc truyện nền của TruyenCloud"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun serviceIntent(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, TtsPlaybackService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun build(context: Context, session: MediaSession?): Notification {
        val b = TtsServiceBridge
        val novelTitle = b.novelTitle.ifBlank { "TruyenCloud" }
        val chapterTitle = b.chapterTitle.ifBlank { "Chương đọc" }
        val isPlaying = b.isPlaying

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val sleepInfo = b.sleepTimerInfo
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(novelTitle)
            .setContentText(if (b.paragraphInfo.isNotBlank()) "$chapterTitle • ${b.paragraphInfo}" else chapterTitle)
            .setSubText(if (sleepInfo.isNotBlank()) "AI Audio • $sleepInfo" else "TruyenCloud AI Audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        b.artwork?.let { builder.setLargeIcon(it) }
        contentIntent?.let { builder.setContentIntent(it) }

        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_previous, "Trước",
                serviceIntent(context, 1, TtsPlaybackService.ACTION_PREV)
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Tạm dừng" else "Đọc tiếp",
                serviceIntent(context, 2, TtsPlaybackService.ACTION_PLAY_PAUSE)
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_next, "Sau",
                serviceIntent(context, 3, TtsPlaybackService.ACTION_NEXT)
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Dừng",
                serviceIntent(context, 4, TtsPlaybackService.ACTION_REMOTE_STOP)
            ).build()
        )

        val mediaStyle = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { mediaStyle.setMediaSession(it.sessionToken) }
        builder.setStyle(mediaStyle)
        return builder.build()
    }
}
