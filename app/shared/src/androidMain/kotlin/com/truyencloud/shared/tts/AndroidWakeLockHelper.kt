package com.truyencloud.shared.tts

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

class AndroidWakeLockHelper(context: Context) {
    private val wakeLock: PowerManager.WakeLock? = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TruyenCloud:TtsWakeLock")?.apply {
            setReferenceCounted(false)
        }
    } catch (_: Exception) {
        null
    }

    // Không giữ WifiLock thì tắt màn hình vài phút là Wi-Fi vào chế độ tiết kiệm,
    // luồng giọng online bị ngắt quãng / hỏng giữa chừng.
    @Suppress("DEPRECATION")
    private val wifiLock: WifiManager.WifiLock? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TruyenCloud:TtsWifiLock")?.apply {
            setReferenceCounted(false)
        }
    } catch (_: Exception) {
        null
    }

    fun acquire(backgroundPlayback: Boolean) {
        try {
            if (backgroundPlayback) {
                wakeLock?.acquire(2 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    fun setWifiLockHeld(held: Boolean) {
        try {
            val lock = wifiLock ?: return
            if (held && !lock.isHeld) lock.acquire()
            if (!held && lock.isHeld) lock.release()
        } catch (_: Exception) {}
    }

    fun release() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        } catch (_: Exception) {}
        setWifiLockHeld(false)
    }
}
