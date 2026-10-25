package com.truyencloud.shared.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Nghe thử giọng ở Cài đặt, chạy trên bộ phát và bộ máy TTS RIÊNG.
 *
 * Trước đây nghe thử gọi `speak()` trên chính bộ đọc truyện: cướp phiên đang nghe, và khi
 * câu mẫu đọc xong thì báo "hết chương" -> tự nhảy sang chương sau. Nay nó không chạm vào
 * trạng thái phiên đọc. Hai bên chỉ phối hợp qua audio focus của hệ điều hành: xin focus
 * tạm thời nên phiên đọc (nếu đang chạy) tự tạm dừng rồi tự phát lại khi nghe thử xong.
 */
internal class AndroidVoicePreview(
    private val context: Context,
    private val mainHandler: Handler,
    private val scope: CoroutineScope,
    private val streamClient: AndroidTtsStreamClient
) {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Lý do lượt này hỏng / ghi chú đã chuyển sang giọng máy; giữ sau khi kết thúc để màn hình không báo "đã xong". */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var samples: List<String> = emptyList()
    private var index = 0
    private var online = true
    private var profile: TtsVoiceProfile = DEFAULT_VOICE_PROFILES.first()
    private var speed = 1.0f
    private var offlineRetries = 0

    // Mỗi lượt nghe thử một "thẻ": callback của lượt cũ (đã bị dừng / thay) bị bỏ qua
    private var generation = 0
    private var player: MediaPlayer? = null
    private var fetchJob: Job? = null
    private var engine: AndroidNativeTtsEngine? = null
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun start(samples: List<String>, profileId: String, onlineMode: Boolean, speed: Float) {
        stop()
        val clean = samples.map { it.trim() }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return
        this.samples = clean
        this.index = 0
        this.online = onlineMode
        // Cùng bộ phân giải với phiên đọc (BaseTtsSpeaker.setVoiceProfile): nghe thử ra đúng giọng sẽ dùng khi đọc truyện
        this.profile = TtsVoiceResolver.profileFor(profileId)
        this.speed = speed
        this.offlineRetries = 0
        // Cùng hồ sơ + cùng thứ tự giọng dự phòng như phiên đọc: nghe thử phải ra ĐÚNG giọng sẽ dùng khi đọc truyện
        engine?.applyVoiceProfile(profile)
        requestFocus()
        _isPlaying.value = true
        playCurrent(generation)
    }

    /** Người dùng dừng (hoặc bắt đầu lượt mới): xoá cả lỗi cũ. */
    fun stop() {
        halt()
        _error.value = null
    }

    private fun halt() {
        generation++
        fetchJob?.cancel()
        fetchJob = null
        releasePlayer()
        engine?.stop()
        abandonFocus()
        _isPlaying.value = false
    }

    fun release() {
        stop()
        engine?.release()
        engine = null
    }

    private fun playCurrent(gen: Int) {
        if (gen != generation) return
        val text = samples.getOrNull(index) ?: return finish(gen)
        if (online) playOnline(text, gen) else playOffline(gen)
    }

    private fun onSampleDone(gen: Int) {
        if (gen != generation) return
        releasePlayer()
        index++
        offlineRetries = 0
        if (index >= samples.size) finish(gen) else playCurrent(gen)
    }

    /** Hết mẫu: kết thúc tự nhiên, giữ ghi chú (nếu có) rằng đã chuyển sang giọng máy. */
    private fun finish(gen: Int) {
        if (gen == generation) halt()
    }

    /** Lượt nghe thử hỏng: báo lý do TRƯỚC khi hạ cờ đang phát để người nghe trạng thái đọc được ngay. */
    private fun fail(gen: Int, message: String) {
        if (gen != generation) return
        _error.value = message
        halt()
    }

    /** Máy chủ không phát được mẫu online: báo rõ rồi đọc CHÍNH mẫu này (và các mẫu sau) bằng giọng máy, như bản cũ. */
    private fun fallBackToOffline(gen: Int) {
        if (gen != generation) return
        releasePlayer()
        online = false
        _error.value = TtsPreviewStatus.ONLINE_FAILED
        playOffline(gen)
    }

    /**
     * Tải mẫu về tệp như phiên đọc ([AndroidTtsStreamClient.fetch]: chờ đủ lâu cho lần dựng tại chỗ, biết 503 / 422) rồi
     * phát tệp với tốc độ của trình phát (máy chủ luôn trả 1.0x). Mẫu tiếng Trung đọc bằng giọng máy.
     */
    private fun playOnline(text: String, gen: Int) {
        if (TtsSegmentPlan.isDeviceOnly(text)) return playOffline(gen)
        fetchJob = scope.launch {
            val result = streamClient.fetch(text, profile.id, null)
            if (gen != generation) return@launch
            if (result is TtsAudioFetch.Ready) playFile(result.path, gen) else fallBackToOffline(gen)
        }
    }

    private fun playFile(path: String, gen: Int) {
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(path)
            mp.setOnPreparedListener { p ->
                mainHandler.post {
                    if (gen != generation || player !== p) return@post
                    // Giữ cao độ (pitch = 1): đọc nhanh / chậm không làm giọng cao lên / trầm xuống
                    runCatching { p.playbackParams = p.playbackParams.setSpeed(speed).setPitch(1.0f) }
                    p.start()
                }
            }
            mp.setOnCompletionListener { mainHandler.post { onSampleDone(gen) } }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Nghe thử online lỗi ($what, $extra)")
                mainHandler.post { fallBackToOffline(gen) }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Không phát được bản nghe thử online: ${e.message}")
            fallBackToOffline(gen)
        }
    }

    private fun playOffline(gen: Int) {
        val tts = engine ?: AndroidNativeTtsEngine(
            context = context,
            onInitSuccess = {
                mainHandler.post {
                    engine?.applyVoiceProfile(profile)
                    speakOfflineNow(generation)
                }
            },
            onInitFailure = { fail(generation, TtsPreviewStatus.FAILED) },
            onPlaybackStarted = {},
            onUtteranceDone = { onSampleDone(generation) },
            onUtteranceError = { code -> onOfflineError(generation, code) }
        ).also { engine = it }
        // Bộ máy đang khởi tạo: onInitSuccess sẽ phát tiếp đúng mẫu hiện tại
        if (tts.isReady) speakOfflineNow(gen)
    }

    private fun speakOfflineNow(gen: Int) {
        if (gen != generation) return
        val tts = engine ?: return
        val text = samples.getOrNull(index) ?: return finish(gen)
        if (!tts.isReady) return
        tts.setSpeechRate(speed)
        // Cùng phép nhận diện ngôn ngữ với phiên đọc và với bản nghe thử online (`lang=` của URL luồng)
        tts.speak(text, index, 1.0f, TtsTextLanguage.langParam(text))
    }

    /** Giọng máy lỗi: thử giọng dự phòng kế tiếp như phiên đọc truyện, rồi mới báo hỏng. */
    private fun onOfflineError(gen: Int, code: Int) {
        if (gen != generation) return
        val tts = engine
        val canRetry = code != AndroidNativeTtsEngine.ERROR_NO_VOICE && offlineRetries < MAX_OFFLINE_RETRIES
        if (canRetry && tts != null && tts.switchToFallbackVoice()) {
            offlineRetries++
            speakOfflineNow(gen)
        } else {
            fail(gen, TtsPreviewStatus.FAILED)
        }
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        try {
            p.reset()
            p.release()
        } catch (_: Exception) {}
    }

    private fun requestFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            focusRequest = req
            audioManager?.requestAudioFocus(req)
        } catch (_: Exception) {}
    }

    private fun abandonFocus() {
        try {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {}
        focusRequest = null
    }

    private companion object {
        const val TAG = "AndroidVoicePreview"
        const val MAX_OFFLINE_RETRIES = 2
    }
}
