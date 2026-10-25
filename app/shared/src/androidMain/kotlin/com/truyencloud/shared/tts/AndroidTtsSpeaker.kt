package com.truyencloud.shared.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * Bộ đọc truyện Android. Chỉ có MỘT thể hiện cho cả tiến trình ([obtain]) để phiên đọc
 * không chết theo Activity (vuốt khỏi đa nhiệm, tạo lại Activity). Máy trạng thái phiên nằm ở
 * [BaseTtsSpeaker]; ở đây chỉ là phần Android: audio focus, dịch vụ foreground, MediaSession,
 * MediaPlayer / TextToSpeech, âm thanh nền.
 */
class AndroidTtsSpeaker private constructor(context: Context) :
    BaseTtsSpeaker(CoroutineScope(Dispatchers.Main + SupervisorJob())), TtsServiceBridge.Callback {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    override val platform: TtsPlatform = TtsPlatform.ANDROID

    // Nhãn lấy từ bộ máy THẬT của máy (Google, Samsung...) thay vì cố định "Google Speech Services"
    override val offlineEngineInfo: String
        get() = router.engineLabel()?.let { "Android Text-to-Speech ($it)" } ?: "Android Text-to-Speech (Google / Samsung)"
    override val offlineVoiceName: String
        get() {
            val voice = router.vietnameseVoiceName()
            return when {
                voice != null -> "$voice (vi-VN)"
                router.lacksVietnameseVoice() -> "Chưa cài giọng tiếng Việt (vi-VN)"
                else -> "Giọng tiếng Việt của hệ thống (vi-VN)"
            }
        }
    override val offlineSetupGuide: String = "Vào Cài đặt Android > Hệ thống / Trợ năng > Chuyển văn bản thành giọng nói (TTS) > Chọn Google Speech Services để tải gói dữ liệu giọng tiếng Việt chất lượng cao."

    private val ambient = AndroidAmbientSoundController()
    override val ambientSoundType: StateFlow<AmbientSoundType> = ambient.ambientSoundType
    override val ambientSoundVolume: StateFlow<Float> = ambient.ambientSoundVolume

    private val streamClient = AndroidTtsStreamClient(scope, appContext)
    private val serviceSync = AndroidTtsServiceSync(appContext, scope) { backgroundPlayback.value }
    private val wordPronouncer = AndroidWordPronouncer(appContext)
    private val preview = AndroidVoicePreview(appContext, mainHandler, scope, streamClient)
    override val isPreviewPlaying: StateFlow<Boolean> = preview.isPlaying
    override val previewError: StateFlow<String?> = preview.error

    private val routerCallbacks: AndroidTtsEngineRouter.Callbacks = object : AndroidTtsEngineRouter.Callbacks {
        override fun onSegmentStarted(index: Int) {
            resources.updateStreaming(router.isStreaming)
            onStateChanged()
        }

        override fun onSegmentFinished(index: Int) = this@AndroidTtsSpeaker.onSegmentFinished(index)
        override fun onFatalError(message: String) = reportFatalError(message)

        override fun onProgress(index: Int, charOffset: Int, audioMs: Long, voiceKey: String?) =
            onSegmentProgress(index, charOffset, audioMs, voiceKey)

        override fun onDeviceFallbackChanged(active: Boolean) {
            if (isDeviceVoiceFallback.value == active) return
            setDeviceVoiceFallback(active)
            onStateChanged()
        }
    }

    private val focusListener: AndroidAudioFocusHelper.Listener = object : AndroidAudioFocusHelper.Listener {
        override fun onFocusGained() {
            setDucked(false)
            onExternalInterruptionEnded(shouldResume = true)
        }

        override fun onFocusLost() = onPlaybackRightsLost()

        // Cuộc gọi / chỉ đường: tạm dừng nhưng GIỮ focus để nhận GAIN rồi tự đọc tiếp
        override fun onFocusLostTransient() = onExternalInterruptionBegan()

        override fun onFocusDuck() = setDucked(true)
    }

    private val resources: AndroidPlaybackResources = AndroidPlaybackResources(appContext, focusListener) {
        // Rút tai nghe / ngắt Bluetooth: dừng hẳn, không tự đọc tiếp ra loa ngoài
        if (isPlaying.value) pause()
    }

    private val router: AndroidTtsEngineRouter =
        AndroidTtsEngineRouter(appContext, mainHandler, scope, streamClient, { isPlaying.value }, { playbackPosition.value }, routerCallbacks)

    init {
        TtsServiceBridge.callback = this
    }

    // region Hook của BaseTtsSpeaker

    override fun acquirePlayback(): PlaybackGrant = when (resources.requestFocus()) {
        AudioFocusResult.GRANTED -> PlaybackGrant.GRANTED
        AudioFocusResult.DELAYED -> PlaybackGrant.DELAYED
        AudioFocusResult.FAILED -> PlaybackGrant.DENIED
    }

    override fun onPlaybackStarted() {
        resources.onPlaying(backgroundPlayback.value, isOnlineMode.value)
        ambient.startIfActive(true, fadeFactor)
        serviceSync.start()
    }

    override fun playSegment(index: Int, from: TtsPlaybackPosition) {
        router.speak(texts, index, from)
        resources.updateStreaming(router.isStreaming)
    }

    override fun pauseAudio(keepFocus: Boolean) {
        router.pause()
        ambient.stop()
        resources.onStopped(abandonFocus = !keepFocus)
    }

    override fun resumeAudio(): Boolean = router.resume()

    override fun stopAudio() {
        router.stopAll()
        ambient.stop()
        resources.onStopped(abandonFocus = true)
        TtsServiceBridge.isPlaying = false
        serviceSync.stop()
    }

    override fun applyVolume(fade: Float, duck: Float) {
        router.setVolume(fade * duck)
        ambient.applyMultiplier(fade)
    }

    override fun onStateChanged() =
        serviceSync.sync(isPlaying.value, currentParagraphIndex.value, texts.size, router.isDeviceVoiceFallback)
    override fun onIdleTimeout() = serviceSync.stop()

    override fun onSessionStarting() {
        router.stopCurrent()
        router.resetFailures()
    }

    override fun onTransitionStarted() = serviceSync.showTransition()
    override fun onSleepLabelChanged(label: String) = serviceSync.setSleepLabel(label)

    override fun onSpeedChanged(speed: Float) = serviceSync.setSpeed(speed)

    // Giọng AI: đổi tốc độ ngay trên trình phát (giữ cao độ); giọng máy: Base đọc lại từ chữ đang đọc
    override fun applySpeedInPlace(speed: Float): Boolean = router.setSpeed(speed)

    override fun onVoiceProfileChanged(profile: TtsVoiceProfile) = router.setProfile(profile)
    override fun onOnlineModeChanged(enabled: Boolean) = router.setOnlineMode(enabled)

    override fun onBackgroundPlaybackChanged(enabled: Boolean) {
        resources.updateBackground(enabled, isPlaying.value)
        if (!enabled) serviceSync.stop() else if (isPlaying.value) serviceSync.start()
    }

    // endregion

    override fun setServerUrl(url: String) = router.setServerUrl(url)

    override fun setMetadata(title: String, subtitle: String) = serviceSync.setMetadata(title, subtitle, isPlaying.value)
    override fun setSegmentParagraphMap(segmentToParagraph: List<Int>, totalParagraphs: Int) = serviceSync.setSegmentMap(segmentToParagraph, totalParagraphs)
    override fun setArtworkUrl(url: String?) = serviceSync.setArtworkUrl(url)

    override fun setAmbientSound(type: AmbientSoundType, volume: Float) = ambient.setAmbientSound(type, volume, isPlaying.value, fadeFactor)
    override fun setAmbientVolume(volume: Float) = ambient.setVolume(volume, fadeFactor)

    override fun previewVoice(samples: List<String>, profileId: String, onlineMode: Boolean, speed: Float) =
        preview.start(samples, profileId, onlineMode, speed.coerceIn(0.5f, 2.0f))

    override fun stopPreview() = preview.stop()
    override fun speakWord(word: String, language: String) = wordPronouncer.speakWord(word, language)

    // Nút ở thông báo / màn hình khoá / tai nghe. Play và Pause là hai lệnh riêng.
    override fun onRemotePlay() { if (!isPlaying.value) resume() }
    override fun onRemotePause() { if (isPlaying.value) pause() }
    override fun onRemotePlayPause() { if (isPlaying.value) pause() else resume() }
    override fun onRemotePrev() = prevParagraph()
    override fun onRemoteNext() = nextParagraph()
    override fun onRemoteStop() = stop()

    override fun release() {
        try { stop() } catch (_: Exception) {}
        preview.release()
        wordPronouncer.shutdown()
        router.release()
        if (TtsServiceBridge.callback === this) TtsServiceBridge.callback = null
        scope.cancel()
        synchronized(lock) { if (instance === this) instance = null }
    }

    companion object {
        private val lock = Any()
        @Volatile private var instance: AndroidTtsSpeaker? = null

        fun obtain(context: Context): AndroidTtsSpeaker =
            instance ?: synchronized(lock) { instance ?: AndroidTtsSpeaker(context).also { instance = it } }

        /**
         * Gọi khi Activity bị huỷ (và KHÔNG phải do đổi cấu hình): đang phát nền thì giữ phiên
         * sống cùng dịch vụ foreground; còn lại thì giải phóng để không rò bộ máy TTS.
         */
        fun releaseIfIdle() {
            val s = instance ?: return
            if (s.isPlaying.value && s.backgroundPlayback.value) return
            s.release()
        }
    }
}
