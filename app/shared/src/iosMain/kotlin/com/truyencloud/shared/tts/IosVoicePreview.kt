@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.truyencloud.shared.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFoundation.AVAudioTimePitchAlgorithmTimeDomain
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.audioTimePitchAlgorithm
import platform.AVFoundation.pause
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.timeControlStatus
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Nghe thử giọng ở Cài đặt, trên bộ phát (AVPlayer) và bộ tổng hợp (AVSpeechSynthesizer)
 * RIÊNG. Không dùng bộ phát của phiên đọc truyện nên không cướp phiên, không kích hoạt
 * "hết chương". [onEnded] báo khi nghe thử kết thúc (để phiên đọc đã tạm dừng nhường chỗ
 * có thể tự phát lại).
 */
internal class IosVoicePreview(
    private val scope: CoroutineScope,
    private val session: IosAudioSessionController,
    private val serverUrl: () -> String,
    private val onEnded: () -> Unit
) {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Lý do lượt này hỏng / ghi chú đã chuyển sang giọng máy; giữ sau khi kết thúc để màn hình không báo "đã xong". */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val player = AVPlayer()
    private val fetcher = TtsAudioFetcher(KtorTtsAudioTransport(), IosTtsAudioFileStore(), scope)
    private val synthesizer = AVSpeechSynthesizer()
    private val delegate = IosSynthesizerDelegate()

    private var generation = 0
    private var samples: List<String> = emptyList()
    private var index = 0
    private var online = true
    private var profile: TtsVoiceProfile = DEFAULT_VOICE_PROFILES.first()
    private var speed = 1.0f
    private var endObserver: Any? = null
    private var watchJob: Job? = null
    private var currentUtterance: AVSpeechUtterance? = null

    init {
        synthesizer.delegate = delegate
        delegate.onUtteranceFinished = { u ->
            if (u === currentUtterance) {
                currentUtterance = null
                onSampleDone(generation)
            }
        }
    }

    fun start(samples: List<String>, profileId: String, onlineMode: Boolean, speed: Float) {
        val clean = samples.map { it.trim() }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return
        val replacing = _isPlaying.value
        halt(releaseSession = false)
        _error.value = null
        if (!replacing && !session.acquire(OWNER)) {
            _error.value = TtsPreviewStatus.FAILED
            return
        }
        this.samples = clean
        this.index = 0
        this.online = onlineMode
        // Cùng bộ phân giải với phiên đọc (BaseTtsSpeaker.setVoiceProfile): nghe thử ra đúng giọng sẽ dùng khi đọc truyện
        this.profile = TtsVoiceResolver.profileFor(profileId)
        this.speed = speed
        _isPlaying.value = true
        playCurrent(generation)
    }

    /** Người dùng dừng: xoá cả lỗi cũ. */
    fun stop() {
        end()
        _error.value = null
    }

    private fun end() {
        val wasPlaying = _isPlaying.value
        halt(releaseSession = true)
        if (wasPlaying) onEnded()
    }

    private fun halt(releaseSession: Boolean) {
        generation++
        removeEndObserver()
        watchJob?.cancel()
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        currentUtterance = null
        if (synthesizer.isSpeaking()) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val wasPlaying = _isPlaying.value
        _isPlaying.value = false
        if (releaseSession && wasPlaying) session.release(OWNER)
    }

    private fun playCurrent(gen: Int) {
        if (gen != generation) return
        val text = samples.getOrNull(index) ?: return finish(gen)
        if (online) playOnline(text, gen) else playOffline(text, gen)
    }

    private fun onSampleDone(gen: Int) {
        if (gen != generation) return
        index++
        if (index >= samples.size) finish(gen) else playCurrent(gen)
    }

    /** Hết mẫu: kết thúc tự nhiên, giữ ghi chú (nếu có) rằng đã chuyển sang giọng máy. */
    private fun finish(gen: Int) {
        if (gen == generation) end()
    }

    /** Lượt nghe thử hỏng: báo lý do TRƯỚC khi hạ cờ đang phát để người nghe trạng thái đọc được ngay. */
    private fun fail(gen: Int, message: String) {
        if (gen != generation) return
        _error.value = message
        end()
    }

    /** Máy chủ không phát được mẫu online: báo rõ rồi đọc CHÍNH mẫu này (và các mẫu sau) bằng giọng máy, như bản cũ. */
    private fun fallBackToOffline(gen: Int, text: String) {
        if (gen != generation) return
        removeEndObserver()
        watchJob?.cancel()
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        online = false
        _error.value = TtsPreviewStatus.ONLINE_FAILED
        playOffline(text, gen)
    }

    /**
     * Tải mẫu về tệp như phiên đọc (chờ đủ lâu cho lần dựng tại chỗ, có token, biết 503 / 422) rồi phát tệp với `rate` của
     * người dùng, giữ cao độ. Mẫu tiếng Trung đọc bằng giọng máy.
     */
    private fun playOnline(text: String, gen: Int) {
        if (TtsSegmentPlan.isDeviceOnly(text)) return playOffline(text, gen)
        val url = TtsStreamUrl.build(serverUrl(), text, null, profile.id)
        val key = TtsAudioPolicy.cacheKey(serverUrl(), profile.id, text)
        watchJob?.cancel()
        watchJob = scope.launch {
            val result = fetcher.fetch(url, key)
            if (gen != generation) return@launch
            if (result !is TtsAudioFetch.Ready) return@launch fallBackToOffline(gen, text)
            val item = AVPlayerItem(uRL = NSURL.fileURLWithPath(result.path))
            item.audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmTimeDomain
            removeEndObserver()
            endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = item,
                queue = NSOperationQueue.mainQueue
            ) { _ -> onSampleDone(gen) }
            player.replaceCurrentItemWithPlayerItem(item)
            player.rate = speed
            val startedAt = TimeSource.Monotonic.markNow()
            while (isActive && gen == generation) {
                val stuck = startedAt.elapsedNow() > START_TIMEOUT && player.timeControlStatus != AVPlayerTimeControlStatusPlaying
                if (item.status == AVPlayerItemStatusFailed || stuck) {
                    fallBackToOffline(gen, text)
                    break
                }
                delay(300L)
            }
        }
    }

    /** Cùng [IosTtsVoiceMatcher.buildUtterance] với phiên đọc truyện: nghe thử đúng giọng / cao độ / tốc độ sẽ đọc thật. */
    private fun playOffline(text: String, gen: Int) {
        val utterance = IosTtsVoiceMatcher.buildUtterance(text, profile, speed)
            ?: return fail(gen, IosVoiceMessages.NO_VIETNAMESE_VOICE)
        currentUtterance = utterance
        synthesizer.speakUtterance(utterance)
    }

    private fun removeEndObserver() {
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
    }

    private companion object {
        const val OWNER = "preview"

        /** Tệp đã nằm trên máy: không phát được sau chừng này là trình phát kẹt. */
        val START_TIMEOUT = 8.seconds
    }
}
