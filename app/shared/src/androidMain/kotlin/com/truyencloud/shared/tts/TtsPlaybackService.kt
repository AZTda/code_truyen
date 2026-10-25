package com.truyencloud.shared.tts

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class TtsPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = TtsNotificationFactory.CHANNEL_ID
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.truyencloud.shared.tts.START"
        const val ACTION_UPDATE = "com.truyencloud.shared.tts.UPDATE"
        const val ACTION_PLAY_PAUSE = "com.truyencloud.shared.tts.PLAY_PAUSE"
        const val ACTION_PREV = "com.truyencloud.shared.tts.PREV"
        const val ACTION_NEXT = "com.truyencloud.shared.tts.NEXT"
        const val ACTION_REMOTE_STOP = "com.truyencloud.shared.tts.REMOTE_STOP"
        const val ACTION_STOP = "com.truyencloud.shared.tts.STOP"
    }

    private var sessionController: TtsMediaSessionController? = null
    private var serviceWakeLock: PowerManager.WakeLock? = null
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TtsServiceBridge.isServiceRunning = true
        TtsNotificationFactory.createChannel(this)
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        serviceWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TruyenCloud:ServiceWakeLock")?.apply {
            setReferenceCounted(false)
        }
        sessionController = TtsMediaSessionController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Lên foreground TRƯỚC mọi thứ: service khởi động bằng startForegroundService mà
        // không gọi startForeground kịp (kể cả khi định dừng ngay) sẽ làm ứng dụng crash.
        ensureForeground()

        val callback = TtsServiceBridge.callback
        val action = intent?.action
        // Không còn bộ máy đọc nào (tiến trình bị hệ thống dựng lại) hoặc intent rỗng ->
        // các nút sẽ "chết", dừng luôn thay vì để một thông báo vô chủ.
        if (action == null || callback == null) {
            cleanStop()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_PLAY_PAUSE -> callback?.onRemotePlayPause()
            ACTION_PREV -> callback?.onRemotePrev()
            ACTION_NEXT -> callback?.onRemoteNext()
            ACTION_REMOTE_STOP -> {
                callback?.onRemoteStop()
                cleanStop()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                cleanStop()
                return START_NOT_STICKY
            }
        }
        manageWakeLock()
        refresh()
        return START_NOT_STICKY
    }

    /** Vuốt ứng dụng khỏi đa nhiệm: đang phát thì phát tiếp, đang dừng thì dọn dẹp. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!TtsServiceBridge.isPlaying) {
            TtsServiceBridge.callback?.onRemoteStop()
            cleanStop()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureForeground() {
        if (isForeground) return
        try {
            val notification = TtsNotificationFactory.build(this, sessionController?.session)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun manageWakeLock() {
        try {
            if (TtsServiceBridge.isPlaying) {
                serviceWakeLock?.acquire(2 * 60 * 60 * 1000L)
            } else if (serviceWakeLock?.isHeld == true) {
                serviceWakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun refresh() {
        sessionController?.update()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, TtsNotificationFactory.build(this, sessionController?.session))
    }

    private fun cleanStop() {
        try {
            if (serviceWakeLock?.isHeld == true) serviceWakeLock?.release()
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        TtsServiceBridge.isServiceRunning = false
        cleanStop()
        sessionController?.release()
        sessionController = null
        super.onDestroy()
    }
}
