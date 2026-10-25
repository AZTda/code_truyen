package com.truyencloud.shared.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Máy trạng thái của phiên đọc, dùng chung cho Android và iOS; mỗi nền tảng chỉ cài các "hook"
 * phát/dừng âm thanh thật ([TtsPlatformHooks]).
 *
 * `isPlaying` là "ý định" của phiên: true từ lúc bắt đầu phát đến khi tạm dừng / dừng / mất quyền
 * phát. Bộ phát con KHÔNG được tự bật nó (sự kiện "đã bắt đầu phát" đến muộn sau khi người dùng
 * bấm dừng từng kéo phiên sống lại). Mọi setter là no-op khi giá trị không đổi, để đẩy lại cài
 * đặt không làm câu đang đọc bị đọc lại.
 *
 * Tiếp tục / đọc lại mảnh hiện tại (đổi giọng, giọng máy không tạm dừng được, bộ phát bị hệ thống thu hồi) luôn bắt đầu từ
 * VỊ TRÍ đang đọc ([playbackPosition]), không về đầu mảnh; chỉ khi đổi sang mảnh khác vị trí mới về đầu mảnh.
 */
abstract class BaseTtsSpeaker(protected val scope: CoroutineScope) : TtsSpeakerState() {

    private val volume = TtsVolumeMixer { fade, duck -> applyVolume(fade, duck) }
    protected val fadeFactor: Float get() = volume.fade
    protected val duckFactor: Float get() = volume.duck

    private var onChapterCompletedListener: (() -> Unit)? = null
    private var pausedByExternal = false
    private var transitionJob: Job? = null
    private var idleJob: Job? = null

    /**
     * Âm thanh đang nạp / tạm dừng ở bộ phát không còn khớp vị trí hay cài đặt của phiên (nhảy đoạn, đổi giọng,
     * nạp văn bản mới lúc tạm dừng): tiếp tục phải đọc lại mảnh hiện tại, không được phát tiếp âm thanh cũ.
     */
    private var audioStale = true

    private val sleepTimer = TtsSleepTimer(
        scope = scope,
        onFade = { volume.setFade(it) },
        onExpired = { pause() },
        onLabelChanged = { onSleepLabelChanged(it) }
    )
    override val sleepTimerMode: StateFlow<SleepTimerMode> = sleepTimer.mode
    override val sleepTimerRemainingSeconds: StateFlow<Int?> = sleepTimer.remainingSeconds

    // region Phiên đọc

    override fun speak(paragraphs: List<String>, startIndex: Int) =
        speakFrom(paragraphs, TtsPlaybackPosition.startOf(startIndex), autoPlay = true)

    override fun speakFrom(paragraphs: List<String>, start: TtsPlaybackPosition, autoPlay: Boolean) {
        if (!autoPlay && !isPlayingState.value) {
            prepareFrom(paragraphs, start)
            return
        }
        cancelTimers()
        pausedByExternal = false
        loadTexts(paragraphs, start)
        if (paragraphs.isEmpty()) {
            stop()
            return
        }
        if (beginPlayback()) speakCurrent() else onStateChanged()
    }

    /**
     * Nạp văn bản mới mà KHÔNG phát nếu phiên đang tạm dừng (người dùng bấm dừng lúc chuyển chương...): bấm Phát ở
     * ứng dụng, thông báo hay tai nghe đều đọc văn bản MỚI từ [startIndex], không quay lại chương cũ.
     */
    override fun prepare(paragraphs: List<String>, startIndex: Int) =
        speakFrom(paragraphs, TtsPlaybackPosition.startOf(startIndex), autoPlay = false)

    private fun prepareFrom(paragraphs: List<String>, start: TtsPlaybackPosition) {
        transitionJob?.cancel()
        transitionJob = null
        loadTexts(paragraphs, start)
        onStateChanged()
    }

    private fun loadTexts(paragraphs: List<String>, start: TtsPlaybackPosition) {
        onTransitionEnded()
        onSessionStarting()
        audioStale = true
        errorState.value = null
        replaceTexts(paragraphs)
        // Đoạn quá ngắn / không dấu ("Minh.", "12") phải theo ngôn ngữ của CHƯƠNG này, không mặc định giọng Anh
        TtsTextLanguage.useContextOf(paragraphs)
        if (paragraphs.isNotEmpty()) setStartPosition(start)
    }

    override fun pause() {
        pausedByExternal = false
        pauseInternal(keepFocus = false)
    }

    override fun resume() {
        if (texts.isEmpty() || isPlayingState.value) return
        pausedByExternal = false
        cancelTimers()
        errorState.value = null
        if (!beginPlayback()) { onStateChanged(); return }
        resumePlayback()
    }

    /** Tiếp tục đúng chỗ dừng; không được (hoặc âm thanh đã cũ) thì đọc lại mảnh hiện tại. */
    private fun resumePlayback() {
        // Tiếp tục tại chỗ không đi qua speakCurrent() nên phải tự báo: thông báo / MediaSession / Now Playing còn "tạm dừng"
        if (audioStale || !resumeAudio()) speakCurrent() else onStateChanged()
    }

    override fun stop() {
        cancelTimers()
        onTransitionEnded()
        pausedByExternal = false
        audioStale = true
        setPlaying(false)
        stopAudio()
        sleepTimer.cancel()
        volume.reset()
    }

    private fun beginPlayback(requestPlayback: Boolean = true): Boolean {
        if (requestPlayback) {
            when (acquirePlayback()) {
                // Vừa xin lại được quyền phát: không còn ai nhường tiếng nữa (sẽ không có GAIN báo về)
                PlaybackGrant.GRANTED -> volume.setDucked(false)
                PlaybackGrant.DELAYED -> { pausedByExternal = true; return false }
                PlaybackGrant.DENIED -> {
                    errorState.value = "Không phát được: ứng dụng khác (cuộc gọi, video...) đang chiếm âm thanh."
                    return false
                }
            }
        }
        idleJob?.cancel()
        setPlaying(true)
        onPlaybackStarted()
        return true
    }

    private fun pauseInternal(keepFocus: Boolean) {
        cancelTimers()
        onTransitionEnded()
        setPlaying(false)
        pauseAudio(keepFocus)
        onStateChanged()
        if (!keepFocus) {
            idleJob = scope.launch {
                delay(IDLE_TIMEOUT_MS)
                if (!isPlayingState.value) onIdleTimeout()
            }
        }
    }

    private fun setPlaying(value: Boolean) {
        isPlayingState.value = value
        sleepTimer.onPlaybackChanged(value)
    }

    /** Phát mảnh hiện tại từ VỊ TRÍ đang đọc (đầu mảnh nếu vừa đổi mảnh). */
    private fun speakCurrent() {
        if (texts.isEmpty()) return
        val idx = TtsSegmentPlan.nextSpeakable(texts, currentIndexState.value)
        if (idx < 0) { handleChapterCompletion(); return }
        alignPositionTo(idx)
        audioStale = false
        onStateChanged()
        playSegment(idx, position)
    }

    private fun advance() {
        val next = TtsSegmentPlan.nextSpeakable(texts, currentIndexState.value + 1)
        if (next < 0) { handleChapterCompletion(); return }
        moveToSegmentStart(next)
        speakCurrent()
    }

    private fun handleChapterCompletion() {
        // "Hết chương": dừng hẳn và KHÔNG báo hoàn tất, nếu không người đọc tự sang chương sau
        if (sleepTimer.consumeChapterEnd()) {
            pauseInternal(keepFocus = false)
            return
        }
        val listener = onChapterCompletedListener
        if (listener == null) {
            pauseInternal(keepFocus = false)
            return
        }
        onTransitionStarted()
        // Giữ phiên trong lúc tải chương sau, nhưng không giữ wake lock / thông báo mãi nếu tải hỏng
        transitionJob?.cancel()
        transitionJob = scope.launch {
            delay(TRANSITION_TIMEOUT_MS)
            if (isPlayingState.value) {
                errorState.value = "Không chuyển được sang chương tiếp theo nên đã dừng đọc."
                pauseInternal(keepFocus = false)
            }
        }
        listener.invoke()
    }

    private fun cancelTimers() {
        transitionJob?.cancel()
        transitionJob = null
        idleJob?.cancel()
        idleJob = null
    }

    /** Bộ phát báo mảnh [index] đã đọc xong. Mảnh cũ (đã bị thay) hoặc phiên đã dừng thì bỏ qua. */
    protected fun onSegmentFinished(index: Int) {
        if (index == currentIndexState.value && isPlayingState.value) advance()
    }

    /** Bộ máy đọc hỏng không cứu được: dừng và báo lỗi. */
    protected fun reportFatalError(message: String) {
        errorState.value = message
        pauseInternal(keepFocus = false)
    }

    // endregion

    // region Mất / lấy lại quyền phát do hệ thống (focus, cuộc gọi, Siri...)

    /** Mất quyền phát TẠM THỜI: tạm dừng, giữ quyền để tự phát lại khi được trả. */
    protected fun onExternalInterruptionBegan() {
        if (!isPlayingState.value) return
        pausedByExternal = true
        pauseInternal(keepFocus = true)
    }

    protected fun onExternalInterruptionEnded(shouldResume: Boolean) {
        if (!pausedByExternal) return
        pausedByExternal = false
        if (!shouldResume || texts.isEmpty() || isPlayingState.value) return
        if (!beginPlayback(requestPlayback = false)) return
        resumePlayback()
    }

    /** Mất quyền phát VĨNH VIỄN (ứng dụng khác chiếm) hoặc rút tai nghe: dừng, không tự phát lại. */
    protected fun onPlaybackRightsLost() {
        pausedByExternal = false
        pauseInternal(keepFocus = false)
        // Không còn giữ focus nên cũng không còn bị nhường tiếng; lúc xin lại sẽ không có GAIN nào báo về
        volume.setDucked(false)
    }

    protected fun setDucked(ducked: Boolean) = volume.setDucked(ducked)

    // endregion

    // region Điều hướng đoạn

    override fun nextParagraph() {
        if (texts.isEmpty()) return
        val next = TtsSegmentPlan.nextSpeakable(texts, currentIndexState.value + 1)
        if (next < 0) { handleChapterCompletion(); return }
        moveTo(next)
    }

    override fun prevParagraph() {
        if (texts.isEmpty()) return
        var prev = currentIndexState.value - 1
        while (prev > 0 && !TtsSegmentPlan.isSpeakable(texts[prev])) prev--
        moveTo(prev.coerceAtLeast(0))
    }

    private fun moveTo(index: Int) {
        val moved = index != currentIndexState.value
        // Đang dừng mà vẫn ở mảnh cũ thì âm thanh còn nạp vẫn đúng chỗ: giữ vị trí để tiếp tục tại chỗ
        if (moved || isPlayingState.value) moveToSegmentStart(index)
        if (isPlayingState.value) {
            speakCurrent()
        } else {
            // Đang tạm dừng: âm thanh còn nạp ở bộ phát là của mảnh cũ
            if (moved) audioStale = true
            onStateChanged()
        }
    }

    // endregion

    // region Cài đặt

    /**
     * Đang phát thì đọc lại mảnh hiện tại ngay; đang dừng thì lúc tiếp tục mới đọc lại (âm thanh cũ sai giọng / tốc độ).
     * Đang chờ chương kế ([transitionJob]) thì bộ phát im và chữ còn giữ là của chương CŨ: đọc lại sẽ lặp câu cuối chương
     * bằng giọng mới rồi báo "hết chương" lần thứ hai. Chương kế nạp xong sẽ tự dùng cài đặt mới.
     */
    private fun restartIfPlaying() {
        if (texts.isEmpty()) return
        if (isPlayingState.value && transitionJob?.isActive != true) speakCurrent() else audioStale = true
    }

    /** Giọng AI: trình phát đổi tốc độ ngay trên âm thanh đang nghe (máy chủ luôn trả 1.0x); giọng máy: đọc lại từ chữ đang đọc. */
    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        if (speedState.value == clamped) return
        speedState.value = clamped
        onSpeedChanged(clamped)
        if (!applySpeedInPlace(clamped)) restartIfPlaying()
    }

    override fun setVoiceProfile(profileId: String) {
        val profile = TtsVoiceResolver.profileFor(profileId)
        if (profileState.value.id == profile.id) return
        profileState.value = profile
        onVoiceProfileChanged(profile)
        restartIfPlaying()
    }

    override fun setVoiceTone(tone: String) = setVoiceProfile(TtsVoiceResolver.resolveToneProfileId(tone))

    override fun setOnlineMode(enabled: Boolean) {
        if (onlineState.value == enabled) return
        onlineState.value = enabled
        onOnlineModeChanged(enabled)
        restartIfPlaying()
    }

    override fun setBackgroundPlayback(enabled: Boolean) {
        if (backgroundState.value == enabled) return
        backgroundState.value = enabled
        onBackgroundPlaybackChanged(enabled)
    }

    override fun setOnChapterCompleted(listener: (() -> Unit)?) { onChapterCompletedListener = listener }
    override fun setSleepTimer(mode: SleepTimerMode) = sleepTimer.set(mode, isPlayingState.value)

    // endregion

    private companion object {
        const val TRANSITION_TIMEOUT_MS = 4 * 60 * 1000L
        const val IDLE_TIMEOUT_MS = 15 * 60 * 1000L
    }
}
