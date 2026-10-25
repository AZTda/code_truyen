@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.truyencloud.shared.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import kotlin.time.TimeSource

/**
 * Bộ đọc truyện iOS. Một thể hiện cho cả tiến trình ([obtain]). Máy trạng thái phiên nằm ở
 * [BaseTtsSpeaker]; ở đây là phần iOS: AVQueuePlayer (giọng online liền mạch), AVSpeechSynthesizer
 * (giọng máy), AVAudioSession, MPRemoteCommandCenter, MPNowPlayingInfoCenter.
 *
 * Đọc từ và nghe thử dùng bộ phát RIÊNG nên không bao giờ làm phiên đọc nhảy đoạn / nhảy chương.
 */
class IosTtsSpeaker private constructor() : BaseTtsSpeaker(CoroutineScope(Dispatchers.Main + SupervisorJob())) {

    private val bgTasks = IosBackgroundTaskManager()
    private val breaker = OnlineTtsCircuitBreaker()

    private var novelTitle = ""
    private var chapterTitle = ""
    private var segmentToParagraph: List<Int> = emptyList()
    private var totalParagraphs = 0
    private var usingOnline = false
    private var pausedForPreview = false

    override val platform: TtsPlatform = TtsPlatform.IOS
    override val offlineEngineInfo: String = "Apple Speech Engine (AVSpeechSynthesizer)"
    override val offlineVoiceName: String get() = IosTtsVoiceMatcher.describeVietnameseVoice()
    override val offlineSetupGuide: String = "Vào Cài đặt iOS > Trợ năng > Nội dung được đọc > Giọng nói > Tiếng Việt > Tải về gói 'Linh (Nâng cao)' để giọng đọc mượt mà tự nhiên hơn."

    private val onlineListener: IosOnlineAudioPlayer.Listener = object : IosOnlineAudioPlayer.Listener {
        override fun onSegmentStarted(index: Int) {
            breaker.recordSuccess()
            updateFallback()
            onStateChanged()
        }

        override fun onSegmentCompleted(index: Int) {
            if (usingOnline) this@IosTtsSpeaker.onSegmentFinished(index)
        }

        /**
         * Lỗi máy chủ THẬT (503, mạng, quá thời gian chờ) mới bật cầu dao ~3 phút; 422 / lỗi phát tệp chỉ đọc riêng mảnh này
         * bằng giọng máy. Máy chủ trả chậm không phải lỗi (bộ tải chờ đủ lâu). Đọc tiếp từ ĐÚNG chữ đang nghe.
         */
        override fun onSegmentFailed(index: Int, result: TtsAudioFetch) {
            if (!usingOnline || index != currentParagraphIndex.value || !isPlaying.value) return
            if (result.isServerFailure) breaker.recordFailure(nowMs())
            usingOnline = false
            online.stopCurrent()
            updateFallback()
            speakOffline(index, playbackPosition.value)
        }

        override fun onSegmentProgress(index: Int, charOffset: Int, audioMs: Long, voiceKey: String) {
            if (usingOnline) this@IosTtsSpeaker.onSegmentProgress(index, charOffset, audioMs, voiceKey)
        }
    }

    private val online: IosOnlineAudioPlayer = IosOnlineAudioPlayer(scope, onlineListener)

    /** Địa chỉ máy chủ giọng online: đổi sang địa chỉ KHÁC thì mở lại cầu dao, bỏ mảnh tải sẵn từ máy chủ cũ. */
    private val server = TtsServerEndpoint(online.serverUrl, breaker) { url ->
        online.serverUrl = url
        online.invalidateBuffer()
    }

    private val offline: IosOfflineSpeech = IosOfflineSpeech(
        onStarted = { onStateChanged() },
        onFinished = { if (!usingOnline) onSegmentFinished(currentParagraphIndex.value) },
        // Thiếu giọng tiếng Việt thì dừng và báo, không để một giọng ngoại ngữ đọc tiếng Việt
        onNoVietnameseVoice = { reportFatalError(IosVoiceMessages.noVietnameseVoice(isOnlineMode.value)) },
        onWord = { offset -> if (!usingOnline) onSegmentProgress(currentParagraphIndex.value, offset) }
    )

    private val session: IosAudioSessionController = IosAudioSessionController(
        onInterruptionBegan = { onExternalInterruptionBegan() },
        onInterruptionEnded = { shouldResume ->
            if (shouldResume) session.reactivate()
            onExternalInterruptionEnded(shouldResume)
        },
        onOutputDeviceLost = { if (isPlaying.value) pause() }
    )

    private val nowPlaying: IosNowPlayingController = IosNowPlayingController(
        scope = scope,
        onPlay = { if (!isPlaying.value) resume() },
        onPause = { if (isPlaying.value) pause() },
        onToggle = { if (isPlaying.value) pause() else resume() },
        onNext = { nextParagraph() },
        onPrevious = { prevParagraph() }
    )

    private val preview: IosVoicePreview = IosVoicePreview(scope, session, { online.serverUrl }, { resumeAfterPreview() })
    override val isPreviewPlaying: StateFlow<Boolean> = preview.isPlaying
    override val previewError: StateFlow<String?> = preview.error

    // Đọc từ: bộ tổng hợp RIÊNG, không delegate, không dùng chung phiên âm thanh (không cắt nhạc của người dùng)
    private val wordSynthesizer = AVSpeechSynthesizer().apply { usesApplicationAudioSession = false }

    // Tắt "phát nền" trong Cài đặt: vào nền (khoá màn hình...) thì dừng đọc
    private val backgroundObserver: Any = NSNotificationCenter.defaultCenter.addObserverForName(
        name = UIApplicationDidEnterBackgroundNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue
    ) { _ -> if (!backgroundPlayback.value && isPlaying.value) pause() }

    // region Hook của BaseTtsSpeaker

    override fun acquirePlayback(): PlaybackGrant =
        if (session.acquire(OWNER_READ)) PlaybackGrant.GRANTED else PlaybackGrant.DENIED

    override fun onPlaybackStarted() = nowPlaying.activate()

    /**
     * Mảnh tiếng Trung: giọng máy đọc THẲNG (VieNeu không đọc tiếng Trung), không gọi máy chủ, không đụng cầu dao.
     * Còn lại: giọng AI nếu người dùng chọn và cầu dao không hạ.
     */
    override fun playSegment(index: Int, from: TtsPlaybackPosition) {
        val text = texts.getOrNull(index)?.trim().orEmpty()
        usingOnline = isOnlineMode.value && !TtsSegmentPlan.isDeviceOnly(text) && breaker.allowOnline(nowMs())
        if (usingOnline) {
            offline.stop()
            online.setVolume(fadeFactor * duckFactor)
            online.play(texts, index, currentVoiceProfile.value.id, speed.value, from)
        } else {
            online.stopCurrent()
            speakOffline(index, from)
        }
        updateFallback()
    }

    private fun speakOffline(index: Int, from: TtsPlaybackPosition) {
        val text = texts.getOrNull(index)?.trim().orEmpty()
        val start = TtsSegmentStart.from(from.takeIf { it.segmentIndex == index } ?: TtsPlaybackPosition.startOf(index), text, null)
        offline.speak(text, currentVoiceProfile.value, speed.value, fadeFactor * duckFactor, start.charOffset)
    }

    /** Người dùng chọn giọng AI nhưng cầu dao đang hạ: thứ đang nghe là giọng của điện thoại. */
    private fun updateFallback() {
        val active = isOnlineMode.value && !usingOnline && breaker.isTripped
        if (isDeviceVoiceFallback.value == active) return
        setDeviceVoiceFallback(active)
        onStateChanged()
    }

    override fun pauseAudio(keepFocus: Boolean) {
        online.pause()
        offline.pause()
    }

    override fun resumeAudio(): Boolean = if (usingOnline) online.resume() else offline.resume()

    override fun stopAudio() {
        online.stopAll()
        offline.stop()
        nowPlaying.deactivate()
        session.release(OWNER_READ)
    }

    override fun applyVolume(fade: Float, duck: Float) = online.setVolume(fade * duck)

    override fun onStateChanged() {
        if (!nowPlaying.isActive) return
        val map = segmentToParagraph.takeIf { it.size == texts.size }.orEmpty()
        val idx = currentParagraphIndex.value
        val number = if (map.isNotEmpty()) (map.getOrNull(idx) ?: map.last()) + 1 else idx + 1
        val total = if (map.isNotEmpty()) totalParagraphs else texts.size
        // Chọn giọng AI nhưng đang nghe giọng của điện thoại (máy chủ lỗi): ghi rõ ở màn hình khoá như Android
        val title = chapterTitle.ifBlank { "Chương đọc" }.let { if (isDeviceVoiceFallback.value) "$it · giọng máy" else it }
        nowPlaying.update(title, novelTitle.ifBlank { "TruyenCloud" }, isPlaying.value, number, total)
    }

    // Dừng lâu thì nhả phiên âm thanh để nhạc của ứng dụng khác có thể tự phát lại
    override fun onIdleTimeout() {
        nowPlaying.deactivate()
        session.release(OWNER_READ)
    }

    override fun onSessionStarting() = nowPlaying.resetElapsed()

    // Giữ ứng dụng sống trong lúc tải chương kế khi đang ở nền
    override fun onTransitionStarted() = bgTasks.beginTransitionBackgroundTask()
    override fun onTransitionEnded() = bgTasks.endTransitionBackgroundTask()

    // Giọng AI: đổi `rate` ngay trên âm thanh đang nghe (giữ cao độ); giọng máy: Base đọc lại từ chữ đang đọc
    override fun applySpeedInPlace(speed: Float): Boolean = online.setSpeed(speed) && usingOnline

    override fun onVoiceProfileChanged(profile: TtsVoiceProfile) {
        online.invalidateBuffer()
        // Người dùng vừa CHỦ ĐỘNG chọn giọng khác (như Android): cho giọng AI cơ hội mới ngay, kẻo vẫn nghe giọng máy tới ~3 phút
        breaker.recordSuccess()
        updateFallback()
    }

    override fun onOnlineModeChanged(enabled: Boolean) {
        breaker.recordSuccess()
        updateFallback()
    }

    // endregion

    override fun stop() {
        pausedForPreview = false
        super.stop()
    }

    override fun setServerUrl(url: String) {
        server.update(url)
    }

    override fun setMetadata(title: String, subtitle: String) {
        novelTitle = title
        chapterTitle = subtitle
        onStateChanged()
    }

    override fun setSegmentParagraphMap(segmentToParagraph: List<Int>, totalParagraphs: Int) {
        this.segmentToParagraph = segmentToParagraph
        this.totalParagraphs = totalParagraphs
    }

    override fun setArtworkUrl(url: String?) = nowPlaying.setArtwork(url) { onStateChanged() }

    // region Nghe thử + đọc từ

    override fun previewVoice(samples: List<String>, profileId: String, onlineMode: Boolean, speed: Float) {
        // iOS không có audio focus: tự tạm dừng phiên đọc để hai giọng không chồng lên nhau, nghe thử xong thì phát lại
        if (isPlaying.value) {
            pausedForPreview = true
            pause()
        }
        preview.start(samples, profileId, onlineMode, speed.coerceIn(0.5f, 2.0f))
        if (!preview.isPlaying.value) resumeAfterPreview()
    }

    override fun stopPreview() = preview.stop()

    private fun resumeAfterPreview() {
        if (!pausedForPreview) return
        pausedForPreview = false
        resume()
    }

    override fun speakWord(word: String, language: String) {
        val clean = word.trim()
        if (clean.isBlank()) return
        val utterance = AVSpeechUtterance(string = clean)
        val isEn = language.startsWith("en", ignoreCase = true) || IosTtsVoiceMatcher.isEnglishText(clean)
        utterance.voice = if (isEn) {
            AVSpeechSynthesisVoice.voiceWithLanguage("en-US") ?: AVSpeechSynthesisVoice.voiceWithLanguage("en")
        } else {
            AVSpeechSynthesisVoice.voiceWithLanguage("vi-VN")
        }
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 0.9f
        wordSynthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        wordSynthesizer.speakUtterance(utterance)
    }

    // endregion

    override fun release() {
        stop()
        preview.stop()
        online.release()
        NSNotificationCenter.defaultCenter.removeObserver(backgroundObserver)
        instance = null
    }

    private fun nowMs(): Long = clockOrigin.elapsedNow().inWholeMilliseconds

    companion object {
        private const val OWNER_READ = "read"
        private val clockOrigin = TimeSource.Monotonic.markNow()
        private var instance: IosTtsSpeaker? = null

        fun obtain(): IosTtsSpeaker = instance ?: IosTtsSpeaker().also { instance = it }
    }
}
