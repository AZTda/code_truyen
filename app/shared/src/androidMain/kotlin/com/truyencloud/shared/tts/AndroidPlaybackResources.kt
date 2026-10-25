package com.truyencloud.shared.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Tài nguyên hệ thống giữ trong lúc phát: audio focus, WakeLock, WifiLock và bộ nhận
 * ACTION_AUDIO_BECOMING_NOISY (rút tai nghe / ngắt Bluetooth -> dừng, không phát ra loa).
 */
internal class AndroidPlaybackResources(
    private val context: Context,
    focusListener: AndroidAudioFocusHelper.Listener,
    private val onBecomingNoisy: () -> Unit
) {
    private val wakeLocks = AndroidWakeLockHelper(context)
    private val audioFocus = AndroidAudioFocusHelper(context, focusListener)
    private var noisyRegistered = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onBecomingNoisy()
        }
    }

    fun requestFocus(): AudioFocusResult = audioFocus.requestAudioFocus()

    fun onPlaying(backgroundPlayback: Boolean, streamingOnline: Boolean) {
        wakeLocks.acquire(backgroundPlayback)
        wakeLocks.setWifiLockHeld(streamingOnline)
        registerNoisy()
    }

    fun updateStreaming(streamingOnline: Boolean) = wakeLocks.setWifiLockHeld(streamingOnline)

    fun updateBackground(backgroundPlayback: Boolean, isPlaying: Boolean) {
        if (backgroundPlayback && isPlaying) wakeLocks.acquire(true) else if (!backgroundPlayback) wakeLocks.release()
    }

    /** [abandonFocus] = false khi dừng vì mất focus TẠM THỜI: phải giữ request để nhận lại GAIN. */
    fun onStopped(abandonFocus: Boolean) {
        wakeLocks.release()
        unregisterNoisy()
        if (abandonFocus) audioFocus.abandonAudioFocus()
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        try {
            ContextCompat.registerReceiver(
                context.applicationContext,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyRegistered = true
        } catch (_: Exception) {}
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try {
            context.applicationContext.unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {}
        noisyRegistered = false
    }
}
