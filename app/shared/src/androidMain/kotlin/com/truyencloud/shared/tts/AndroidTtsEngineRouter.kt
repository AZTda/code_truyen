package com.truyencloud.shared.tts

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope

/**
 * Chọn bộ máy đọc cho từng mảnh: giọng AI (tệp do máy chủ dựng) hay giọng của điện thoại.
 *
 * - Mảnh tiếng Trung: giọng máy đọc THẲNG, không gọi máy chủ, không đụng cầu dao (VieNeu không đọc tiếng Trung).
 * - Giọng AI lỗi THẬT (503, mất mạng, quá [TtsAudioPolicy.STREAM_TIMEOUT_MS]) -> đọc CHÍNH mảnh đó bằng giọng máy từ chữ
 *   đang đọc và bật cầu dao ~3 phút. Máy chủ trả chậm không phải lỗi. 422 / lỗi phát tệp chỉ đổi giọng cho riêng mảnh đó.
 * - Giọng máy lỗi -> đổi giọng dự phòng rồi đọc lại CHÍNH mảnh đó. Lỗi liên tiếp [MAX_OFFLINE_FAILURES] lần -> [Callbacks.onFatalError].
 * Mọi đường dự phòng đều kiểm tra phiên còn đang phát ([isPlaying]).
 */
internal class AndroidTtsEngineRouter(
    context: Context,
    private val mainHandler: Handler,
    scope: CoroutineScope,
    private val streamClient: AndroidTtsStreamClient,
    private val isPlaying: () -> Boolean,
    private val position: () -> TtsPlaybackPosition,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onSegmentStarted(index: Int)
        fun onSegmentFinished(index: Int)
        fun onFatalError(message: String)
        fun onProgress(index: Int, charOffset: Int, audioMs: Long, voiceKey: String?)
        fun onDeviceFallbackChanged(active: Boolean)
    }

    var onlineMode: Boolean = true
        private set
    var profile: TtsVoiceProfile = TtsVoiceCatalog.defaultProfile()
        private set
    var speed: Float = 1.0f
        private set
    private var volume: Float = 1.0f

    private var texts: List<String> = emptyList()
    private var currentIndex: Int = -1
    private var usingOnline = false
    private var pendingOffline: TtsPlaybackPosition? = null

    /** Vị trí trong chữ của mảnh mà phần giọng máy đang đọc bắt đầu (đọc tiếp giữa mảnh = đọc phần đuôi). */
    private var deviceBase = 0

    private val breaker = OnlineTtsCircuitBreaker()
    private val offlineFailures = ConsecutiveFailureTracker(MAX_OFFLINE_FAILURES)

    private val online = AndroidOnlineTtsPlayer(
        context = context,
        mainHandler = mainHandler,
        scope = scope,
        streamClient = streamClient,
        isSessionPlaying = isPlaying,
        listener = object : AndroidOnlineTtsPlayer.Listener {
            override fun onSegmentStarted(index: Int) {
                breaker.recordSuccess()
                reportFallback()
                callbacks.onSegmentStarted(index)
            }

            override fun onSegmentCompleted(index: Int) {
                if (index == currentIndex && isPlaying()) callbacks.onSegmentFinished(index)
            }

            override fun onSegmentFailed(index: Int, result: TtsAudioFetch) {
                if (index != currentIndex || !isPlaying()) return
                if (result.isServerFailure) breaker.recordFailure(SystemClock.elapsedRealtime())
                speakOffline(index, position())
            }

            override fun onSegmentProgress(index: Int, charOffset: Int, audioMs: Long, voiceKey: String) {
                if (index == currentIndex && usingOnline) callbacks.onProgress(index, charOffset, audioMs, voiceKey)
            }
        }
    )

    /** Địa chỉ máy chủ giọng online: đổi sang địa chỉ KHÁC thì mở lại cầu dao, bỏ mảnh tải sẵn từ máy chủ cũ. */
    private val server = TtsServerEndpoint(streamClient.serverUrl, breaker) { url ->
        streamClient.serverUrl = url
        offlineFailures.reset()
        online.invalidateBuffer()
        streamClient.resetPrefetchWindow()
        reportFallback()
    }

    private val native: AndroidNativeTtsEngine = AndroidNativeTtsEngine(
        context = context,
        onInitSuccess = {
            native.setSpeechRate(speed)
            native.applyVoiceProfile(profile)
            pendingOffline?.let { if (isPlaying()) speakOffline(currentIndex, it) }
        },
        onInitFailure = {
            if (pendingOffline != null && isPlaying()) {
                pendingOffline = null
                callbacks.onFatalError("Thiết bị chưa có bộ máy đọc tiếng Việt. Hãy bật giọng AI online hoặc cài Google Speech Services.")
            }
        },
        onPlaybackStarted = {
            offlineFailures.reset()
            if (!usingOnline) callbacks.onSegmentStarted(currentIndex)
        },
        onUtteranceDone = {
            if (!usingOnline && isPlaying()) callbacks.onSegmentFinished(currentIndex)
        },
        onUtteranceError = { code -> handleOfflineError(code) },
        onWordStarted = { start -> if (!usingOnline) callbacks.onProgress(currentIndex, deviceBase + start, -1L, null) }
    )

    /** Đang phát (hoặc sắp phát) qua mạng — để giữ WifiLock. */
    val isStreaming: Boolean get() = onlineMode && usingOnline

    /** Người dùng chọn giọng AI nhưng cầu dao đang hạ: thứ đang nghe là giọng MÁY. */
    val isDeviceVoiceFallback: Boolean get() = onlineMode && !usingOnline && breaker.isTripped

    private fun reportFallback() = callbacks.onDeviceFallbackChanged(isDeviceVoiceFallback)

    fun speak(texts: List<String>, index: Int, from: TtsPlaybackPosition) {
        this.texts = texts
        currentIndex = index
        val text = texts.getOrNull(index).orEmpty()
        if (!SpeakableText.isSpeakable(text)) {
            skipUnspeakable(index)
            return
        }
        if (onlineMode && !TtsSegmentPlan.isDeviceOnly(text) && breaker.allowOnline(SystemClock.elapsedRealtime())) {
            usingOnline = true
            pendingOffline = null
            native.stop()
            online.play(texts, index, profile.id, speed, from)
            streamClient.maybePrefetch(texts, index, profile.id)
            reportFallback()
        } else {
            speakOffline(index, from)
        }
    }

    /**
     * Mảnh chỉ có dấu câu / ký hiệu ("***", "——", "……"): máy chủ không có gì để đọc, mà một lần lỗi online là cầu dao hạ
     * cả phiên xuống giọng máy ~3 phút. Bỏ qua mảnh, không đụng tới cầu dao.
     */
    private fun skipUnspeakable(index: Int) {
        usingOnline = false
        pendingOffline = null
        online.stopCurrent()
        native.stop()
        mainHandler.post { if (index == currentIndex && isPlaying()) callbacks.onSegmentFinished(index) }
    }

    private fun speakOffline(index: Int, from: TtsPlaybackPosition) {
        usingOnline = false
        online.stopCurrent()
        reportFallback()
        if (!isPlaying()) return
        if (!native.isReady) {
            pendingOffline = from
            return
        }
        pendingOffline = null
        val text = texts.getOrNull(index)?.trim().orEmpty()
        // Nghe tiếp giữa mảnh: đọc từ ĐẦU TỪ đang đọc dở, không đọc lại cả mảnh
        val start = TtsSegmentStart.from(from.takeIf { it.segmentIndex == index } ?: TtsPlaybackPosition.startOf(index), text, null)
        deviceBase = start.charOffset.coerceIn(0, text.length)
        val part = text.substring(deviceBase)
        // Cùng phép nhận diện ngôn ngữ với `lang=` của URL luồng: giọng máy và giọng AI luôn đọc đoạn bằng cùng một ngôn ngữ
        native.speak(part, index, volume, TtsTextLanguage.langParam(text))
    }

    private fun handleOfflineError(code: Int) {
        if (usingOnline || !isPlaying()) return
        if (code == AndroidNativeTtsEngine.ERROR_NO_VOICE) {
            // Không giọng tiếng Việt nào dùng được: đọc bằng giọng ngoại là sai giọng, thử lại cũng vô ích
            offlineFailures.reset()
            callbacks.onFatalError(if (onlineMode) NO_VOICE_AFTER_ONLINE_FAILED else NO_VOICE_OFFLINE)
            return
        }
        if (offlineFailures.recordFailure()) {
            offlineFailures.reset()
            callbacks.onFatalError("Giọng đọc của máy gặp lỗi liên tục nên đã dừng. Hãy kiểm tra gói giọng tiếng Việt trong Cài đặt Android.")
            return
        }
        native.switchToFallbackVoice()
        speakOffline(currentIndex, position())
    }

    fun pause() {
        pendingOffline = null
        online.pause()
        native.stop()
    }

    /** Tiếp tục đúng chỗ (chỉ giọng AI tạm dừng được); false thì người gọi đọc lại từ chữ đang đọc. */
    fun resume(): Boolean = usingOnline && onlineMode && online.resume()

    fun stopCurrent() {
        pendingOffline = null
        online.stopCurrent()
        native.stop()
    }

    fun stopAll() {
        pendingOffline = null
        online.stopAll()
        native.stop()
        streamClient.resetPrefetchWindow()
    }

    fun setVolume(value: Float) {
        volume = value
        online.setVolume(value)
    }

    /** true nếu tốc độ mới được áp ngay trên âm thanh giọng AI đang nạp; giọng máy phải đọc lại từ chữ đang đọc. */
    fun setSpeed(value: Float): Boolean {
        speed = value
        native.setSpeechRate(value)
        return online.setSpeed(value) && usingOnline
    }

    fun setProfile(value: TtsVoiceProfile) {
        profile = value
        native.applyVoiceProfile(value)
        online.invalidateBuffer()
        streamClient.resetPrefetchWindow()
        // Người dùng vừa CHỦ ĐỘNG chọn giọng khác: cho giọng AI cơ hội mới ngay, kẻo vẫn nghe giọng máy tới ~3 phút
        breaker.recordSuccess()
        reportFallback()
    }

    fun setServerUrl(url: String) {
        server.update(url)
    }

    fun setOnlineMode(enabled: Boolean) {
        onlineMode = enabled
        // Người dùng chủ động chọn lại chế độ -> cho giọng online cơ hội mới ngay
        breaker.recordSuccess()
        offlineFailures.reset()
        reportFallback()
    }

    /** Nhãn bộ máy / giọng máy THẬT cho màn hình Cài đặt (thay nhãn cố định "Google Speech Services"). */
    fun engineLabel(): String? = native.engineLabel()

    fun vietnameseVoiceName(): String? = native.vietnameseVoiceName()
    fun lacksVietnameseVoice(): Boolean = native.lacksVietnameseVoice()

    fun resetFailures() {
        offlineFailures.reset()
        native.resetVoiceFallback()
    }

    fun release() {
        stopAll()
        native.release()
    }

    private companion object {
        const val MAX_OFFLINE_FAILURES = 3
        const val NO_VOICE_OFFLINE =
            "Máy chưa có giọng đọc tiếng Việt nên không đọc offline được. Hãy cài gói giọng tiếng Việt (Cài đặt Android > " +
                "Chuyển văn bản thành giọng nói) hoặc bật giọng AI online."
        const val NO_VOICE_AFTER_ONLINE_FAILED =
            "Không kết nối được giọng AI online và máy chưa có giọng tiếng Việt để đọc thay. Hãy kiểm tra mạng, địa chỉ máy " +
                "chủ hoặc cài gói giọng tiếng Việt trong Cài đặt Android."
    }
}
