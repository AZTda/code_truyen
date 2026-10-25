package com.truyencloud.shared.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Bộ đọc giả: ghi lại các lệnh nền tảng mà máy trạng thái [BaseTtsSpeaker] phát ra. */
internal class FakeTtsSpeaker(var grant: PlaybackGrant = PlaybackGrant.GRANTED) :
    BaseTtsSpeaker(CoroutineScope(Dispatchers.Unconfined + SupervisorJob())) {

    val log = mutableListOf<String>()

    /** Văn bản của từng lần `play:` (đúng thứ tự), để biết bộ phát đang đọc chữ của phiên nào. */
    val spoken = mutableListOf<String>()

    /** `isPlaying` tại mỗi lần `onStateChanged()` — thông báo, MediaSession, Now Playing chỉ biết trạng thái qua đây. */
    val stateLog = mutableListOf<Boolean>()

    /** Giả lập bộ phát có (true) hay không (false) khả năng tiếp tục đúng chỗ đã dừng. */
    var resumable = true

    /** Mô hình bộ phát thật: mảnh đang nạp (kể cả lúc tạm dừng) và có đang nghe được không. */
    var loaded: Int? = null
    var audible = false

    override fun acquirePlayback(): PlaybackGrant = grant.also { log += "acquire:$it" }
    override fun onPlaybackStarted() { log += "started" }
    /** Vị trí bắt đầu của từng lần `play:` (đúng thứ tự). */
    val starts = mutableListOf<TtsPlaybackPosition>()

    /** Giả lập trình phát giọng AI đổi được tốc độ ngay trên âm thanh đang nạp. */
    var speedInPlace = false

    override fun playSegment(index: Int, from: TtsPlaybackPosition) {
        log += "play:$index"
        spoken += texts[index]
        starts += from
        loaded = index
        audible = true
    }

    override fun applySpeedInPlace(speed: Float): Boolean = speedInPlace && loaded != null
    override fun pauseAudio(keepFocus: Boolean) { log += "pause:keep=$keepFocus"; audible = false }
    override fun resumeAudio(): Boolean {
        log += "resume"
        // Như bộ phát thật: chỉ tiếp tục được khi còn mảnh đang nạp
        val ok = resumable && loaded != null
        if (ok) audible = true
        return ok
    }
    override fun stopAudio() { log += "stop"; loaded = null; audible = false }
    override fun applyVolume(fade: Float, duck: Float) { log += "volume:${fade * duck}" }
    override fun onStateChanged() { stateLog += isPlaying.value }
    override fun onIdleTimeout() { log += "idle" }
    override fun onSpeedChanged(speed: Float) { log += "speed:$speed" }
    override fun onVoiceProfileChanged(profile: TtsVoiceProfile) { log += "voice:${profile.id}" }
    override fun onOnlineModeChanged(enabled: Boolean) { log += "online:$enabled" }

    fun plays() = log.filter { it.startsWith("play:") }
    fun volumes() = log.filter { it.startsWith("volume:") }
    fun finish(index: Int) = onSegmentFinished(index)

    /** Âm thanh đang NGHE ĐƯỢC phát hết -> báo hoàn tất mảnh đó (như onCompletion / DidPlayToEnd); đang tạm dừng thì không có gì kết thúc. */
    fun audioEnds() {
        val index = loaded ?: return
        if (!audible) return
        loaded = null
        audible = false
        onSegmentFinished(index)
    }

    fun interruptionBegan() = onExternalInterruptionBegan()
    fun interruptionEnded(resume: Boolean) = onExternalInterruptionEnded(resume)
    fun rightsLost() = onPlaybackRightsLost()
    fun fatal(message: String) = reportFatalError(message)
    fun duck(ducked: Boolean) = setDucked(ducked)
    fun progress(index: Int, charOffset: Int, audioMs: Long = -1L, voiceKey: String? = null) =
        onSegmentProgress(index, charOffset, audioMs, voiceKey)
}
