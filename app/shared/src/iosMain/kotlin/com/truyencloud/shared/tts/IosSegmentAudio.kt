@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.truyencloud.shared.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAudioTimePitchAlgorithmTimeDomain
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.audioTimePitchAlgorithm
import platform.Foundation.NSURL

/**
 * Âm thanh giọng AI của MỘT mảnh trên iOS: tải tệp ([TtsAudioFetcher]) rồi dựng `AVPlayerItem` trên tệp cục bộ. Trình phát
 * ([IosOnlineAudioPlayer]) gắn mục vào `AVPlayer`, tua tới [start] và phát với tốc độ của người dùng.
 *
 * Tốc độ đổi bằng `AVPlayer.rate`; [AVAudioTimePitchAlgorithmTimeDomain] giữ nguyên cao độ (co giãn thời gian, hợp giọng
 * nói), không phải Varispeed (đổi tông như băng chạy nhanh).
 */
internal class IosSegmentAudio(
    val index: Int,
    val key: String,
    val text: String,
    val voiceId: String
) {
    var start: TtsSegmentStart = TtsSegmentStart.BEGINNING
    var path: String? = null
        private set
    var durationMs: Long = 0L
        private set

    /** Tải hỏng (503 / 422 / mạng): trình phát báo lỗi khi tới lượt mảnh này. */
    var failure: TtsAudioFetch? = null
        private set
    var item: AVPlayerItem? = null
        private set
    var attachedAtMs: Long = -1L
    var started = false
    var paused = false
    var released = false
        private set

    /** Đã mở lại tệp một lần sau khi trình phát hỏng giữa chừng (không lặp vô hạn). */
    var reopened = false
    var lastPositionMs = 0L

    private var job: Job? = null

    val isLoaded: Boolean get() = path != null || failure != null

    fun load(scope: CoroutineScope, fetch: suspend () -> TtsAudioFetch, onLoaded: (IosSegmentAudio) -> Unit) {
        job = scope.launch {
            val result = fetch()
            if (released) return@launch
            if (result is TtsAudioFetch.Ready) {
                path = result.path
                durationMs = result.durationMs
            } else {
                failure = result
            }
            onLoaded(this@IosSegmentAudio)
        }
    }

    /** Mục phát MỚI cho tệp đã tải (mỗi lần gắn vào trình phát một mục mới: mục cũ có thể đã hỏng). */
    fun newItem(): AVPlayerItem? {
        val file = path ?: return null
        val url = NSURL.fileURLWithPath(file)
        return AVPlayerItem(uRL = url).also {
            it.audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmTimeDomain
            item = it
        }
    }

    /** Bỏ mục đang gắn (để mở lại tệp từ đầu ở lần gắn sau). */
    fun detach() {
        item = null
        attachedAtMs = -1L
        started = false
        paused = false
    }

    /** ms cần tua tới khi bắt đầu: đúng ms đã lưu của cùng giọng, hoặc quy từ vị trí chữ (đổi giọng / chỉ còn vị trí chữ). */
    fun seekMs(): Long = when {
        start.audioMs >= 0L -> start.audioMs
        start.charOffset > 0 -> TtsTextTiming.audioMsAt(text, start.charOffset, durationMs)
        else -> 0L
    }.coerceIn(0L, (durationMs - 50L).coerceAtLeast(0L))

    fun release() {
        if (released) return
        released = true
        job?.cancel()
        item = null
    }

    companion object {
        /** Lỗi phát tệp trên máy (không phải lỗi máy chủ): đọc mảnh đó bằng giọng máy, không bật cầu dao. */
        const val PLAYBACK_ERROR = "playback_error"
    }
}
