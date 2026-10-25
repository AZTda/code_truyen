@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.truyencloud.shared.tts

import com.truyencloud.shared.api.ServerUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.volume
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import kotlin.time.TimeSource

/**
 * Bộ phát giọng AI của iOS, cùng cách làm với Android: tải âm thanh từng mảnh về tệp ([TtsAudioFetcher], chờ đủ lâu cho lần
 * dựng tại chỗ, phân biệt 503 / 422) rồi phát tệp bằng MỘT `AVPlayer`, tải sẵn [TtsSegmentPlan.BUFFER_AHEAD] mảnh phía trước
 * (bỏ qua mảnh tiếng Trung: giọng máy đọc).
 *
 * Phát bản cũ thẳng từ URL bằng AVQueuePlayer: không gửi được token, không biết mã lỗi, coi 25 giây chờ là hỏng (máy chủ dựng
 * tại chỗ có thể lâu hơn) và hàng đợi tự nhảy qua mảnh tiếng Trung. Nay tốc độ do trình phát áp (`rate`, giữ cao độ), khoá
 * mảnh = giọng + chữ (không có tốc độ), và vị trí được báo về ~5 lần/giây.
 */
internal class IosOnlineAudioPlayer(
    private val scope: CoroutineScope,
    private val listener: Listener
) {
    interface Listener {
        fun onSegmentStarted(index: Int)
        fun onSegmentCompleted(index: Int)

        /** [result]: lỗi máy chủ (503 / mạng / hết giờ) hay chỉ mảnh này không đọc được bằng giọng AI (422, lỗi phát tệp). */
        fun onSegmentFailed(index: Int, result: TtsAudioFetch)
        fun onSegmentProgress(index: Int, charOffset: Int, audioMs: Long, voiceKey: String)
    }

    var serverUrl: String = ServerUrls.DEFAULT

    private val fetcher = TtsAudioFetcher(KtorTtsAudioTransport(), IosTtsAudioFileStore(), scope)
    private val player = AVPlayer()
    private var current: IosSegmentAudio? = null
    private val ahead = mutableMapOf<Int, IosSegmentAudio>()
    private var texts: List<String> = emptyList()
    private var profileId: String = ""
    private var speed = 1.0f
    private var volume = 1.0f
    private var wantPlaying = false
    private var tickJob: Job? = null

    private val endObserver: Any = NSNotificationCenter.defaultCenter.addObserverForName(
        AVPlayerItemDidPlayToEndTimeNotification, null, NSOperationQueue.mainQueue
    ) { n ->
        val e = current ?: return@addObserverForName
        val ended = n?.`object` as? AVPlayerItem
        if (ended != null && ended === e.item) {
            releaseCurrent()
            listener.onSegmentCompleted(e.index)
        }
    }

    val hasActiveSegment: Boolean get() = current != null

    /** Phát mảnh [index] từ [from] (đầu mảnh, hoặc đúng chỗ đang nghe dở). */
    fun play(texts: List<String>, index: Int, profileId: String, speed: Float, from: TtsPlaybackPosition) {
        this.texts = texts
        this.profileId = profileId
        this.speed = speed
        wantPlaying = true
        val text = texts.getOrNull(index)?.trim().orEmpty()
        val key = keyFor(text)
        val start = TtsSegmentStart.from(from, text, profileId)
        // Đang tải đúng mảnh này (bấm dừng lúc chờ rồi phát lại): dùng tiếp, không tải lại
        val existing = current?.takeIf { it.index == index && it.key == key && !it.released && !it.started }
        if (existing == null) {
            releaseCurrent()
            val candidate = ahead.remove(index)
            val reused = candidate?.takeIf { it.key == key && !it.started }
            if (candidate != null && reused == null) candidate.release()
            current = reused ?: createEntry(index, text, key)
        }
        current?.start = start
        tryStart()
        startTicking()
    }

    fun pause() {
        wantPlaying = false
        current?.let { e ->
            if (e.started) {
                e.lastPositionMs = positionMs()
                e.paused = true
            }
        }
        player.pause()
        tickJob?.cancel()
        tickJob = null
    }

    /** Tiếp tục đúng chỗ đã dừng (không tải lại); false nếu chưa có gì đang phát (Base sẽ gọi lại [play]). */
    fun resume(): Boolean {
        val e = current ?: return false
        if (!e.started || e.item?.status == AVPlayerItemStatusFailed) return false
        wantPlaying = true
        e.paused = false
        player.volume = volume
        player.rate = speed
        startTicking()
        return true
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        player.volume = volume
    }

    /** Đổi tốc độ ngay trên âm thanh đang nghe; true nếu có mảnh giọng AI đang nạp (không cần đọc lại). */
    fun setSpeed(value: Float): Boolean {
        speed = value
        val e = current ?: return false
        if (e.started && wantPlaying) player.rate = value
        return true
    }

    fun stopCurrent() {
        wantPlaying = false
        releaseCurrent()
    }

    /** Bỏ các mảnh đã tải sẵn (đổi giọng / máy chủ làm chúng không còn đúng). */
    fun invalidateBuffer() {
        ahead.values.forEach { it.release() }
        ahead.clear()
    }

    fun stopAll() {
        stopCurrent()
        invalidateBuffer()
        tickJob?.cancel()
        tickJob = null
    }

    fun release() {
        stopAll()
        NSNotificationCenter.defaultCenter.removeObserver(endObserver)
    }

    private fun keyFor(text: String) = "$profileId|$text"

    private fun createEntry(index: Int, text: String, key: String): IosSegmentAudio {
        val entry = IosSegmentAudio(index, key, text, profileId)
        val url = TtsStreamUrl.build(serverUrl, text, TtsSegmentPlan.prefetchHint(texts, index), profileId)
        val fileKey = TtsAudioPolicy.cacheKey(serverUrl, profileId, text)
        entry.load(scope, { fetcher.fetch(url, fileKey) }, ::onLoaded)
        return entry
    }

    private fun onLoaded(entry: IosSegmentAudio) {
        if (entry === current) {
            tryStart()
        } else if (entry.failure != null) {
            // Mảnh tải sẵn hỏng: bỏ đi, tới lượt nó sẽ được tải lại
            ahead.entries.firstOrNull { it.value === entry }?.key?.let { ahead.remove(it) }
            entry.release()
        }
    }

    /** Gắn tệp của mảnh hiện tại vào trình phát, chờ sẵn sàng, tua tới điểm bắt đầu rồi phát. */
    private fun tryStart() {
        val e = current ?: return
        if (!wantPlaying || e.started || !e.isLoaded) return
        e.failure?.let { return fail(e, it) }
        if (e.item == null) {
            val item = e.newItem() ?: return fail(e, TtsAudioFetch.Unsupported(IosSegmentAudio.PLAYBACK_ERROR))
            e.attachedAtMs = nowMs()
            player.replaceCurrentItemWithPlayerItem(item)
            player.volume = volume
        }
        val item = e.item ?: return
        when (item.status) {
            AVPlayerItemStatusReadyToPlay -> {
                val seek = e.seekMs()
                val zero = CMTimeMake(value = 0, timescale = 1)
                if (seek > 0L) player.seekToTime(CMTimeMakeWithSeconds(seek / 1000.0, 1000), zero, zero)
                // `rate` khác 0 là bắt đầu phát, đúng tốc độ người dùng chọn (máy chủ luôn trả 1.0x)
                player.rate = speed
                e.started = true
                e.paused = false
                listener.onSegmentStarted(e.index)
                refillBuffer(e.index)
            }
            AVPlayerItemStatusFailed -> onPlayerError(e)
            else -> if (nowMs() - e.attachedAtMs > ITEM_READY_TIMEOUT_MS) onPlayerError(e)
        }
    }

    /**
     * Trình phát hỏng (mục phát lỗi, dịch vụ âm thanh của hệ thống khởi động lại): mở lại CHÍNH tệp này một lần và tua về chỗ
     * đang nghe (lùi [TtsPlaybackPosition.RESUME_BACKOFF_MS]); hỏng nữa thì báo để đọc mảnh này bằng giọng máy.
     */
    private fun onPlayerError(e: IosSegmentAudio) {
        if (!e.reopened && e.path != null) {
            e.reopened = true
            val resumeAt = if (e.started) (e.lastPositionMs - TtsPlaybackPosition.RESUME_BACKOFF_MS).coerceAtLeast(0L) else e.seekMs()
            e.detach()
            e.start = TtsSegmentStart(charOffset = 0, audioMs = resumeAt, voiceKey = e.voiceId)
            player.replaceCurrentItemWithPlayerItem(null)
            tryStart()
            return
        }
        fail(e, TtsAudioFetch.Unsupported(IosSegmentAudio.PLAYBACK_ERROR))
    }

    private fun fail(e: IosSegmentAudio, result: TtsAudioFetch) {
        if (e !== current) return
        val playing = wantPlaying
        releaseCurrent()
        // Đang dừng thì không đọc thay bằng giọng máy; lúc tiếp tục sẽ thử lại
        if (playing) listener.onSegmentFailed(e.index, result)
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    private fun tick() {
        val e = current ?: return
        if (!e.started) return tryStart()
        if (e.item?.status == AVPlayerItemStatusFailed) return onPlayerError(e)
        if (!wantPlaying || e.paused) return
        val ms = positionMs()
        if (ms >= 0L) {
            e.lastPositionMs = ms
            listener.onSegmentProgress(e.index, TtsTextTiming.charOffsetAt(e.text, ms, e.durationMs), ms, e.voiceId)
        }
    }

    private fun positionMs(): Long {
        val seconds = CMTimeGetSeconds(player.currentTime())
        return if (seconds.isNaN() || seconds < 0.0) -1L else (seconds * 1000.0).toLong()
    }

    private fun refillBuffer(currentIdx: Int) {
        val wanted = TtsSegmentPlan.serverAhead(texts, currentIdx)
        val iterator = ahead.entries.iterator()
        while (iterator.hasNext()) {
            val (idx, e) = iterator.next()
            if (idx !in wanted || e.key != keyFor(texts.getOrNull(idx)?.trim().orEmpty())) {
                e.release()
                iterator.remove()
            }
        }
        for (idx in wanted) {
            if (idx in ahead) continue
            val text = texts[idx].trim()
            ahead[idx] = createEntry(idx, text, keyFor(text))
        }
        IosTtsAudioFileStore.prefetchUpcoming(scope, serverUrl, texts, currentIdx, profileId)
    }

    private fun releaseCurrent() {
        val e = current ?: return
        current = null
        e.release()
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
    }

    private fun nowMs(): Long = clockOrigin.elapsedNow().inWholeMilliseconds

    private companion object {
        const val TICK_MS = 200L

        /** Tệp cục bộ thường sẵn sàng trong vài chục ms; quá lâu là trình phát kẹt. */
        const val ITEM_READY_TIMEOUT_MS = 8_000L
        val clockOrigin = TimeSource.Monotonic.markNow()
    }
}
