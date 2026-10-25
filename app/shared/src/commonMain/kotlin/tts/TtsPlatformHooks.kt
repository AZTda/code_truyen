package com.truyencloud.shared.tts

/** Kết quả xin quyền phát âm thanh (audio focus / AVAudioSession). */
enum class PlaybackGrant { GRANTED, DELAYED, DENIED }

/**
 * Các "hook" mà mỗi nền tảng (Android, iOS) cài cho máy trạng thái [BaseTtsSpeaker]: xin quyền phát,
 * phát / dừng âm thanh thật, đồng bộ thông báo và màn hình khoá. Tách riêng để [BaseTtsSpeaker] chỉ còn
 * logic chuyển trạng thái.
 */
abstract class TtsPlatformHooks : TtsSpeaker {

    /** Xin quyền phát. DELAYED = sẽ được cấp sau (đang có cuộc gọi...), DENIED = bị từ chối. */
    protected abstract fun acquirePlayback(): PlaybackGrant

    /** Phiên vừa được phép phát: giữ wake lock, bật dịch vụ nền / màn hình khoá, âm thanh nền... */
    protected abstract fun onPlaybackStarted()

    /**
     * Bắt đầu phát mảnh [index] (đã chọn giọng online / giọng máy) từ vị trí [from]: đầu mảnh, hoặc giữa mảnh khi nghe tiếp
     * (dựng điểm bắt đầu bằng [TtsSegmentStart.from] với giọng sẽ phát thật).
     */
    protected abstract fun playSegment(index: Int, from: TtsPlaybackPosition)

    /** Dừng âm thanh nhưng GIỮ vị trí. [keepFocus] = true khi dừng vì mất quyền phát tạm thời. */
    protected abstract fun pauseAudio(keepFocus: Boolean)

    /** Tiếp tục đúng chỗ đã dừng; false nếu không thể (Base sẽ đọc lại mảnh hiện tại). */
    protected abstract fun resumeAudio(): Boolean

    /** Dừng hẳn, nhả mọi tài nguyên. */
    protected abstract fun stopAudio()

    protected abstract fun applyVolume(fade: Float, duck: Float)

    /** Trạng thái phiên đổi: làm mới thông báo / thông tin màn hình khoá. */
    protected abstract fun onStateChanged()

    /** Tạm dừng quá lâu: nhả dịch vụ nền / phiên âm thanh (phiên vẫn còn). */
    protected abstract fun onIdleTimeout()

    protected open fun onSessionStarting() {}
    protected open fun onTransitionStarted() {}
    protected open fun onTransitionEnded() {}
    protected open fun onSleepLabelChanged(label: String) {}
    protected open fun onSpeedChanged(speed: Float) {}

    /**
     * Áp tốc độ mới NGAY TRÊN âm thanh đang nạp (giọng AI: tốc độ của trình phát, giữ cao độ). true = không cần đọc lại;
     * false (giọng máy, chưa có gì đang nạp) thì phiên đọc lại từ đúng chữ đang đọc.
     */
    protected open fun applySpeedInPlace(speed: Float): Boolean = false
    protected open fun onVoiceProfileChanged(profile: TtsVoiceProfile) {}
    protected open fun onOnlineModeChanged(enabled: Boolean) {}
    protected open fun onBackgroundPlaybackChanged(enabled: Boolean) {}
}
