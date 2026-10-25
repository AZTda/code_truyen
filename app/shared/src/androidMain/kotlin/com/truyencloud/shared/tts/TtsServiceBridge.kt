package com.truyencloud.shared.tts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap

/**
 * Cầu nối giao tiếp giữa TtsSpeaker và TtsPlaybackService Foreground Service.
 */
object TtsServiceBridge {
    interface Callback {
        fun onRemotePlay()
        fun onRemotePause()
        fun onRemotePlayPause()
        fun onRemoteNext()
        fun onRemotePrev()
        fun onRemoteStop()
    }

    @Volatile var callback: Callback? = null
    @Volatile var isPlaying: Boolean = false
    var novelTitle: String = "TruyenCloud Đang Đọc"
    var chapterTitle: String = "Chương truyện"
    var paragraphInfo: String = ""
    var sleepTimerInfo: String = ""
    var paragraphNumber: Int = 0
    var paragraphTotal: Int = 0
    var playbackSpeed: Float = 1.0f
    var artwork: Bitmap? = null

    /** Service đang sống — tránh việc "cập nhật" vô tình KHỞI ĐỘNG lại một service đã dừng. */
    @Volatile var isServiceRunning: Boolean = false

    fun sync(playing: Boolean, label: String, number: Int, total: Int) {
        isPlaying = playing
        paragraphInfo = label
        paragraphNumber = number
        paragraphTotal = total
    }

    fun startService(context: Context) {
        // Dịch vụ đã là foreground: chỉ cần làm mới. Gọi lại startForegroundService lúc ứng dụng ở
        // nền (tự chuyển chương khi tắt màn hình) có thể bị Android 12+ từ chối.
        if (isServiceRunning) {
            updateService(context)
            return
        }
        try {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = TtsPlaybackService.ACTION_START
            }
            // Đánh dấu ngay: lệnh dừng gửi liền sau (trước khi onCreate chạy) vẫn phải tới được service
            isServiceRunning = true
            context.startForegroundService(intent)
        } catch (e: Exception) {
            isServiceRunning = false
            e.printStackTrace()
        }
    }

    fun updateService(context: Context) {
        if (!isServiceRunning) return
        try {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = TtsPlaybackService.ACTION_UPDATE
            }
            context.startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopService(context: Context) {
        if (!isServiceRunning) return
        val intent = Intent(context, TtsPlaybackService::class.java)
        try {
            // Đi qua onStartCommand để service kịp startForeground trước khi tự dừng
            context.startService(intent.apply { action = TtsPlaybackService.ACTION_STOP })
        } catch (_: Exception) {
            try { context.stopService(intent) } catch (_: Exception) {}
        }
    }
}
