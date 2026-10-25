package com.truyencloud.shared.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Âm thanh giọng AI của MỘT mảnh: tải tệp ([AndroidTtsStreamClient.fetch]) rồi chuẩn bị MediaPlayer trên tệp cục bộ.
 *
 * Tốc độ áp bằng `PlaybackParams` (giữ cao độ = 1.0, tức co giãn thời gian chứ không đổi tông). Lưu ý của Android: đặt
 * PlaybackParams với tốc độ khác 0 lúc đang Prepared / Paused sẽ TỰ PHÁT, nên chỉ đặt khi đang phát hoặc ngay lúc bắt đầu.
 */
internal class AndroidSegmentAudio(
    val index: Int,
    val key: String,
    val text: String,
    val voiceId: String,
    private val context: Context,
    private val mainHandler: Handler,
    private val events: Events
) {
    interface Events {
        fun onPrepared(audio: AndroidSegmentAudio)
        fun onCompleted(audio: AndroidSegmentAudio)
        fun onFailed(audio: AndroidSegmentAudio, result: TtsAudioFetch)
    }

    var start: TtsSegmentStart = TtsSegmentStart.BEGINNING
    var prepared = false
        private set
    var started = false
        private set
    var paused = false
        private set
    var released = false
        private set

    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var path: String? = null
    private var fileDurationMs = 0L
    private var reopened = false
    private var lastPositionMs = 0L

    val durationMs: Long get() = player?.takeIf { prepared }?.duration?.toLong()?.takeIf { it > 0 } ?: fileDurationMs

    fun load(scope: CoroutineScope, client: AndroidTtsStreamClient, hint: String?) {
        job = scope.launch {
            val result = client.fetch(text, voiceId, hint)
            if (released) return@launch
            if (result is TtsAudioFetch.Ready) open(result.path, result.durationMs) else events.onFailed(this@AndroidSegmentAudio, result)
        }
    }

    private fun open(filePath: String, duration: Long) {
        path = filePath
        fileDurationMs = duration
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setWakeMode(context.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(filePath)
            mp.setOnPreparedListener { p -> mainHandler.post { if (!released && p === player) { prepared = true; events.onPrepared(this) } } }
            mp.setOnCompletionListener { p -> mainHandler.post { if (!released && p === player) events.onCompleted(this) } }
            mp.setOnErrorListener { p, what, extra ->
                Log.w(TAG, "Lỗi phát tệp giọng AI mảnh #$index ($what, $extra)")
                mainHandler.post { if (!released && p === player) onPlayerError() }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Không mở được tệp giọng AI: ${e.message}")
            events.onFailed(this, TtsAudioFetch.Unsupported(PLAYBACK_ERROR))
        }
    }

    /**
     * Trình phát hỏng giữa chừng (vd. media server của hệ thống khởi động lại): mở lại CHÍNH tệp này một lần và tua về chỗ
     * đang nghe (lùi [TtsPlaybackPosition.RESUME_BACKOFF_MS]); hỏng nữa thì báo để đọc mảnh này bằng giọng máy.
     */
    private fun onPlayerError() {
        val file = path
        if (started && !reopened && file != null) {
            reopened = true
            val resumeAt = (lastPositionMs - TtsPlaybackPosition.RESUME_BACKOFF_MS).coerceAtLeast(0L)
            releasePlayer()
            prepared = false
            started = false
            start = TtsSegmentStart(charOffset = 0, audioMs = resumeAt, voiceKey = voiceId)
            open(file, fileDurationMs)
            return
        }
        events.onFailed(this, TtsAudioFetch.Unsupported(PLAYBACK_ERROR))
    }

    /** Bắt đầu phát từ [start] với [speed] / [volume]; false nếu trình phát từ chối. */
    fun begin(speed: Float, volume: Float): Boolean {
        val mp = player ?: return false
        if (!prepared || started) return started
        return try {
            val seekMs = when {
                start.audioMs >= 0L -> start.audioMs
                start.charOffset > 0 -> TtsTextTiming.audioMsAt(text, start.charOffset, durationMs)
                else -> 0L
            }
            if (seekMs > 0L) mp.seekTo(seekMs.coerceAtMost((durationMs - 50L).coerceAtLeast(0L)), MediaPlayer.SEEK_CLOSEST)
            mp.setVolume(volume, volume)
            applySpeed(mp, speed)
            mp.start()
            started = true
            paused = false
            true
        } catch (e: Exception) {
            Log.w(TAG, "Không bắt đầu phát được mảnh #$index: ${e.message}")
            false
        }
    }

    fun pause() {
        val mp = player ?: return
        if (!started || paused) return
        runCatching { lastPositionMs = mp.currentPosition.toLong(); mp.pause() }
        paused = true
    }

    /** Tiếp tục đúng chỗ đã dừng (không tải lại, không mở lại). */
    fun resume(speed: Float, volume: Float): Boolean {
        val mp = player ?: return false
        if (!started || !paused) return false
        return try {
            mp.setVolume(volume, volume)
            applySpeed(mp, speed)
            mp.start()
            paused = false
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Đổi tốc độ ngay khi đang phát; đang dừng / chưa phát thì tốc độ được áp lúc bắt đầu / tiếp tục. */
    fun setSpeed(speed: Float) {
        val mp = player ?: return
        if (started && !paused) runCatching { applySpeed(mp, speed) }
    }

    fun setVolume(volume: Float) {
        val mp = player ?: return
        if (prepared) runCatching { mp.setVolume(volume, volume) }
    }

    /** ms đang phát trong tệp (để quy ra chữ đang đọc), hoặc -1. */
    fun positionMs(): Long {
        val mp = player ?: return -1L
        if (!started) return -1L
        return runCatching { mp.currentPosition.toLong() }.getOrDefault(-1L).also { if (it >= 0) lastPositionMs = it }
    }

    fun release() {
        if (released) return
        released = true
        job?.cancel()
        releasePlayer()
    }

    private fun releasePlayer() {
        val mp = player ?: return
        player = null
        runCatching { mp.reset(); mp.release() }
    }

    private fun applySpeed(mp: MediaPlayer, speed: Float) {
        // pitch = 1: Android co giãn thời gian (Sonic), giọng không bị cao lên / trầm xuống khi đọc nhanh / chậm
        mp.playbackParams = mp.playbackParams.setSpeed(speed.coerceIn(0.5f, 2.0f)).setPitch(1.0f)
    }

    companion object {
        private const val TAG = "AndroidSegmentAudio"

        /** Lỗi phát tệp trên máy (không phải lỗi máy chủ): đọc mảnh đó bằng giọng máy, không bật cầu dao. */
        const val PLAYBACK_ERROR = "playback_error"
    }
}
