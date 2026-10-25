package com.truyencloud.shared.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Một "hồ sơ giọng" người dùng chọn. Giọng AI là giọng thật do máy chủ tự chạy (VieNeu-TTS, danh mục ở [TtsVoiceCatalog]);
 * mười hồ sơ cũ ([DEFAULT_VOICE_PROFILES]) vẫn được nhận. Id được lưu trong hồ sơ người dùng và gửi lên máy chủ, KHÔNG đổi.
 */
@Serializable
data class TtsVoiceProfile(
    val id: String,
    val name: String,
    /** Giọng VieNeu: MIỀN thật ("Bắc", "Trung", "Nam"). Hồ sơ cũ: [LegacyVoices.GROUP]. */
    val region: String,
    val gender: String, // "Nữ", "Nam", "Tự động" (giọng máy: không ràng buộc giới tính)
    val tag: String, // phong cách đọc ("Đọc truyện", "Kể chuyện", "Tin tức", "Tự nhiên") hoặc sắc thái của hồ sơ cũ
    val description: String,
    val targetVoicePrefix: String, // tiền tố tên giọng của máy (chỉ dùng cho giọng máy): "vic", "vie", "vif", "vid", "gft", "vi-vn"
    val pitch: Float = 1.0f
)

/** Lời giải thích dùng chung cho màn hình chọn giọng AI. */
const val TTS_VOICE_CATALOG_NOTE =
    "Giọng đọc do MiniPC của bạn tạo sẵn, nghe liền mạch. Đoạn tiếng Trung được đọc bằng giọng có sẵn trên điện thoại."

enum class SleepTimerMode(val label: String, val minutes: Int) {
    OFF("Tắt", 0),
    MIN_15("15p", 15),
    MIN_30("30p", 30),
    MIN_45("45p", 45),
    MIN_60("60p", 60),
    END_OF_CHAPTER("Hết chương", -1)
}

enum class AmbientSoundType(val label: String, val iconName: String) {
    NONE("Tắt", "volume_off"),
    RAIN("Mưa đêm", "water_drop"),
    FIREPLACE("Lò sưởi", "local_fire_department"),
    OCEAN("Sóng biển", "waves"),
    CAFE("Quán cafe", "local_cafe")
}

enum class TtsPlatform {
    ANDROID,
    IOS
}

/**
 * Multiplatform Text-To-Speech Interface for novel reading.
 * Controls audio playback, paragraph progression, speech speed,
 * background playback (screen-off / outside app), ambient relaxation sounds,
 * curated AI voice profiles, and intelligent sleep timer.
 */
interface TtsSpeaker {
    val platform: TtsPlatform get() = TtsPlatform.ANDROID
    val offlineEngineInfo: String get() = "Động cơ hệ thống"
    val offlineVoiceName: String get() = "Tiếng Việt (vi-VN)"
    val offlineSetupGuide: String get() = ""

    val isPlaying: StateFlow<Boolean>
    val currentParagraphIndex: StateFlow<Int>
    val speed: StateFlow<Float>
    val backgroundPlayback: StateFlow<Boolean> get() = MutableStateFlow(true)
    val currentVoiceProfile: StateFlow<TtsVoiceProfile> get() = MutableStateFlow(TtsVoiceCatalog.defaultProfile())

    /** Đang đọc tới đâu: mảnh, ký tự trong mảnh và ms âm thanh (giọng AI). Để lưu và nghe tiếp ĐÚNG chữ đã dừng. */
    val playbackPosition: StateFlow<TtsPlaybackPosition> get() = MutableStateFlow(TtsPlaybackPosition())

    /** Người dùng chọn giọng AI nhưng đang nghe giọng của điện thoại vì máy chủ lỗi / không phản hồi. */
    val isDeviceVoiceFallback: StateFlow<Boolean> get() = MutableStateFlow(false)

    // Sleep Timer states
    val sleepTimerMode: StateFlow<SleepTimerMode> get() = MutableStateFlow(SleepTimerMode.OFF)
    val sleepTimerRemainingSeconds: StateFlow<Int?> get() = MutableStateFlow(null)

    // Ambient Relaxation Sound states
    val ambientSoundType: StateFlow<AmbientSoundType> get() = MutableStateFlow(AmbientSoundType.NONE)
    val ambientSoundVolume: StateFlow<Float> get() = MutableStateFlow(0.25f)

    // Online AI Voice vs Offline system Voice mode
    val isOnlineMode: StateFlow<Boolean> get() = MutableStateFlow(true)

    /** Lỗi khiến phiên đọc phải dừng (vd. bộ máy đọc hỏng liên tiếp); null khi bình thường. */
    val lastError: StateFlow<String?> get() = MutableStateFlow(null)

    /** Nghe thử giọng ở Cài đặt chạy trên bộ phát RIÊNG, không đụng tới phiên đọc truyện. */
    val isPreviewPlaying: StateFlow<Boolean> get() = MutableStateFlow(false)

    /**
     * Lý do lượt nghe thử gần nhất không nghe được (máy chủ không phản hồi, bộ máy đọc hỏng) hoặc ghi chú đã
     * chuyển sang giọng máy; null khi bình thường. Xoá khi bắt đầu lượt mới, giữ lại sau khi lượt nghe thử kết thúc
     * để màn hình không báo "đã nghe xong" cho một lượt chẳng có tiếng nào.
     */
    val previewError: StateFlow<String?> get() = MutableStateFlow(null)

    fun speak(paragraphs: List<String>, startIndex: Int = 0)

    /**
     * Nạp văn bản của phiên mới mà KHÔNG phát nếu đang tạm dừng — để nút Phát (ứng dụng, thông báo, tai nghe) đọc
     * đúng văn bản mới từ [startIndex]. Đang phát thì tương đương [speak].
     */
    fun prepare(paragraphs: List<String>, startIndex: Int = 0) {}

    /**
     * Như [speak] / [prepare] nhưng bắt đầu từ ĐÚNG chữ đã lưu ([TtsPlaybackPosition.charOffset], hoặc ms âm thanh của
     * cùng giọng AI) thay vì đầu mảnh.
     */
    fun speakFrom(paragraphs: List<String>, start: TtsPlaybackPosition, autoPlay: Boolean = true) {
        if (autoPlay) speak(paragraphs, start.segmentIndex) else prepare(paragraphs, start.segmentIndex)
    }
    fun pause()
    fun resume()
    fun stop()
    fun setSpeed(speed: Float)
    fun nextParagraph()
    fun prevParagraph()
    fun setBackgroundPlayback(enabled: Boolean) {}
    fun setVoiceProfile(profileId: String) {}
    fun setVoiceTone(tone: String) {}
    fun setMetadata(title: String, subtitle: String) {}
    fun setOnChapterCompleted(listener: (() -> Unit)?) {}
    fun setOnlineMode(enabled: Boolean) {}
    fun setServerUrl(url: String) {}

    /**
     * Ánh xạ mảnh TTS -> đoạn hiển thị, để thông báo / màn hình khoá đánh số
     * "Đoạn x/y" giống hệt thanh điều khiển trong trình đọc.
     */
    fun setSegmentParagraphMap(segmentToParagraph: List<Int>, totalParagraphs: Int) {}
    fun setArtworkUrl(url: String?) {}

    fun previewVoice(samples: List<String>, profileId: String, onlineMode: Boolean, speed: Float) {}
    fun stopPreview() {}

    // Sleep Timer & Ambient Audio controls
    fun setSleepTimer(mode: SleepTimerMode) {}
    fun setAmbientSound(type: AmbientSoundType, volume: Float = 0.25f) {}
    fun setAmbientVolume(volume: Float) {}

    // Pronunciation of individual words for English learning & dictionary lookup
    fun speakWord(word: String, language: String = "en") {}

    /** Giai phong toan bo tai nguyen bo may doc (goi khi Activity bi huy). */
    fun release() {}
}
