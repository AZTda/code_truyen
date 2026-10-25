package com.truyencloud.shared.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

internal enum class AudioFocusResult { GRANTED, DELAYED, FAILED }

/**
 * Quyền phát âm thanh (audio focus). Phân biệt mất hẳn / mất tạm thời / được phép
 * vặn nhỏ, vì chỉ trường hợp tạm thời (cuộc gọi, chỉ đường) mới được tự phát lại.
 */
class AndroidAudioFocusHelper(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onFocusGained()
        fun onFocusLost()
        fun onFocusLostTransient()
        fun onFocusDuck()
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    // Dùng MỘT request cố định để abandon đúng cái đã xin
    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(::onFocusChange, Handler(Looper.getMainLooper()))
            .build()
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> listener.onFocusGained()
            AudioManager.AUDIOFOCUS_LOSS -> listener.onFocusLost()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> listener.onFocusLostTransient()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener.onFocusDuck()
        }
    }

    internal fun requestAudioFocus(): AudioFocusResult {
        val manager = audioManager ?: return AudioFocusResult.GRANTED
        return try {
            when (manager.requestAudioFocus(focusRequest)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> AudioFocusResult.GRANTED
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> AudioFocusResult.DELAYED
                else -> AudioFocusResult.FAILED
            }
        } catch (_: Exception) {
            AudioFocusResult.GRANTED
        }
    }

    fun abandonAudioFocus() {
        try {
            audioManager?.abandonAudioFocusRequest(focusRequest)
        } catch (_: Exception) {}
    }
}
