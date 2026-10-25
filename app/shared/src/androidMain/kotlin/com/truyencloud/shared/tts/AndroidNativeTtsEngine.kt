package com.truyencloud.shared.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Bọc TextToSpeech của hệ thống. Chỉ BÁO lỗi ([onUtteranceError]); việc đổi giọng
 * rồi đọc lại CHÍNH đoạn đó, hay dừng hẳn sau nhiều lần lỗi, do người gọi quyết định.
 *
 * Giọng được chọn THEO NGÔN NGỮ của đoạn ([speak]) qua [NativeVoiceSelector]; mọi `setVoice` / `setLanguage` đều kiểm
 * tra kết quả. Bản cũ bỏ qua kết quả nên khi giọng không nạp được, bộ máy giữ nguyên giọng trước đó (thường là giọng
 * tiếng Anh vừa đọc, hoặc giọng theo ngôn ngữ máy) và đọc tiếng Việt bằng giọng ngoại mà không ai hay biết.
 */
class AndroidNativeTtsEngine(
    context: Context,
    private val onInitSuccess: () -> Unit,
    private val onInitFailure: () -> Unit = {},
    private val onPlaybackStarted: () -> Unit,
    private val onUtteranceDone: () -> Unit,
    private val onUtteranceError: (errorCode: Int) -> Unit,
    /** Bộ máy sắp đọc từ bắt đầu ở [start] (đơn vị UTF-16 trong chữ vừa đưa cho [speak]); minSdk 26 nên luôn có. */
    private val onWordStarted: (start: Int) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    var isReady: Boolean = false
        private set

    private var currentUtteranceId: String = ""

    private var voicesByName: Map<String, Voice> = emptyMap()
    private var voiceInfos: List<NativeVoiceInfo> = emptyList()
    private var profile: TtsVoiceProfile? = null

    /** Thứ tự thử giọng theo ngôn ngữ ("vi" / "en" / "zh") cho hồ sơ hiện tại. */
    private val ranked = HashMap<String, List<NativeVoiceInfo>>()

    /** Vị trí giọng đang dùng trong [ranked]; tăng lên khi giọng đó báo lỗi. */
    private val cursor = HashMap<String, Int>()
    private var appliedVoiceName: String? = null
    private var appliedPitch: Float? = null
    private var lastLanguage: String = LANG_VI

    // PHẢI khai báo SAU mọi thuộc tính khác: máy không có engine TTS nào thì Android gọi onInit(ERROR)
    // ĐỒNG BỘ ngay trong constructor TextToSpeech, lúc đó mainHandler & các trường trên phải đã khởi tạo.
    private var tts: TextToSpeech? = try {
        TextToSpeech(context.applicationContext, this)
    } catch (e: Exception) {
        Log.e(TAG, "Không khởi tạo được TextToSpeech: ${e.message}")
        mainHandler.post(onInitFailure)
        null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            setupAudioAttributes()
            loadVoices()
            setupListener()
            isReady = true
            onInitSuccess()
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
            mainHandler.post(onInitFailure)
        }
    }

    private fun setupAudioAttributes() {
        try {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring AudioAttributes: ${e.message}")
        }
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    /** Đọc lại danh sách giọng của bộ máy (lúc khởi tạo, hoặc khi người dùng vừa cài gói giọng). */
    private fun loadVoices() {
        val raw: Set<Voice> = try {
            tts?.voices.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing voices: ${e.message}")
            emptySet()
        }
        voicesByName = raw.associateBy { it.name }
        voiceInfos = raw.map { it.toNativeInfo() }
        ranked.clear()
        cursor.clear()
        appliedVoiceName = null
    }

    /** Giọng ĐÃ TẢI của [lang], đúng thứ tự thử cho hồ sơ hiện tại (giọng chưa tải không đọc được, nên không thử). */
    private fun rankedFor(lang: String): List<NativeVoiceInfo> =
        ranked.getOrPut(lang) { NativeVoiceSelector.rank(voiceInfos, lang, profile).filter { it.installed } }

    /** Đổi hồ sơ giọng: xếp lại thứ tự giọng; giọng thật được nạp ở lần [speak] kế tiếp. */
    fun applyVoiceProfile(profile: TtsVoiceProfile) {
        this.profile = profile
        ranked.clear()
        cursor.clear()
    }

    /** Tên bộ máy đọc mặc định của hệ thống (vd. "Google Speech Services"); null nếu chưa biết. */
    fun engineLabel(): String? = try {
        when (val pkg = tts?.defaultEngine) {
            null, "" -> null
            "com.google.android.tts" -> "Google Speech Services"
            "com.samsung.SMT" -> "Samsung Text-to-speech"
            else -> pkg
        }
    } catch (_: Exception) {
        null
    }

    /** Giọng tiếng Việt đã tải sẽ được dùng cho hồ sơ hiện tại; null khi chưa sẵn sàng hoặc máy chưa có giọng nào. */
    fun vietnameseVoiceName(): String? = if (isReady) rankedFor(LANG_VI).firstOrNull()?.name else null

    /** Bộ máy đã sẵn sàng nhưng KHÔNG có giọng tiếng Việt đã tải. */
    fun lacksVietnameseVoice(): Boolean = isReady && rankedFor(LANG_VI).isEmpty()

    /** Về giọng tốt nhất cho phiên mới: lỗi tạm thời của một giọng không được ám cả những phiên sau. */
    fun resetVoiceFallback() {
        cursor.clear()
    }

    /** Chuyển sang giọng dự phòng kế tiếp CỦA NGÔN NGỮ vừa lỗi; false nếu đã thử hết. */
    fun switchToFallbackVoice(): Boolean {
        val list = rankedFor(lastLanguage)
        val next = (cursor[lastLanguage] ?: 0) + 1
        if (next >= list.size) return false
        cursor[lastLanguage] = next
        return true
    }

    /**
     * Nạp giọng cho [lang]. Trả false khi KHÔNG có giọng tiếng Việt nào dùng được (đọc tiếng Việt bằng giọng ngoại là
     * sai; người gọi phải báo lỗi). Anh / Trung thiếu giọng thì vẫn đọc bằng giọng hiện có, còn hơn im lặng.
     */
    private fun selectVoice(engine: TextToSpeech, lang: String): Boolean {
        if (lang == LANG_VI && rankedFor(lang).isEmpty()) loadVoices()
        val list = rankedFor(lang)
        var i = cursor[lang] ?: 0
        while (i < list.size) {
            val info = list[i]
            val voice = voicesByName[info.name]
            val applied = appliedVoiceName == info.name ||
                (voice != null && runCatching { engine.setVoice(voice) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS)
            if (applied) {
                cursor[lang] = i
                appliedVoiceName = info.name
                return true
            }
            i++
        }
        val locales = when (lang) {
            LANG_EN -> listOf(Locale.US, Locale.ENGLISH)
            LANG_ZH -> listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE)
            else -> listOf(Locale.forLanguageTag("vi-VN"))
        }
        for (locale in locales) {
            val result = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (result >= TextToSpeech.LANG_AVAILABLE) {
                appliedVoiceName = null
                return true
            }
        }
        Log.w(TAG, "Không có giọng '$lang' dùng được trên máy (${list.size} giọng đã thử)")
        return lang != LANG_VI
    }

    /**
     * Hồ sơ chỉnh cao độ cho giọng Việt (hạ thêm khi hồ sơ nam phải đọc bằng giọng nữ); máy chủ đọc Anh / Trung ở cao độ
     * chuẩn nên giọng máy cũng vậy.
     */
    private fun applyPitch(engine: TextToSpeech, lang: String) {
        val want = NativeVoiceSelector.pitchFor(profile, lang, appliedVoiceName)
        if (appliedPitch == want) return
        runCatching { engine.setPitch(want) }
        appliedPitch = want
    }

    private fun setupListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post { if (utteranceId == currentUtteranceId) onPlaybackStarted() }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post { if (utteranceId == currentUtteranceId) onUtteranceDone() }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                mainHandler.post { if (utteranceId == currentUtteranceId) onWordStarted(start) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                reportError(utteranceId, errorCode)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                reportError(utteranceId, -1)
            }
        })
    }

    private fun reportError(utteranceId: String?, errorCode: Int) {
        mainHandler.post {
            if (utteranceId.isNullOrEmpty() || utteranceId != currentUtteranceId) return@post
            Log.e(TAG, "TTS synthesis error ($errorCode) on utterance: $utteranceId")
            currentUtteranceId = ""
            onUtteranceError(errorCode)
        }
    }

    /** [language] là `TtsTextLanguage.langParam` của đoạn: "vi" | "en" | "zh". */
    fun speak(text: String, idx: Int, volumeMultiplier: Float, language: String) {
        val engine = tts ?: return
        if (!isReady) return
        val lang = NativeVoiceSelector.canonicalLanguage(language).let { if (it == LANG_EN || it == LANG_ZH) it else LANG_VI }
        val uid = "utt_${System.currentTimeMillis()}_$idx"
        currentUtteranceId = uid
        lastLanguage = lang
        if (!selectVoice(engine, lang)) {
            reportError(uid, ERROR_NO_VOICE)
            return
        }
        applyPitch(engine, lang)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volumeMultiplier.coerceIn(0.05f, 1.0f))
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid)
        if (result == TextToSpeech.ERROR) reportError(uid, TextToSpeech.ERROR)
    }

    fun stop() {
        currentUtteranceId = ""
        tts?.stop()
    }

    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        } finally {
            tts = null
            isReady = false
        }
    }

    companion object {
        private const val TAG = "AndroidNativeTtsEngine"
        private const val LANG_VI = "vi"
        private const val LANG_EN = "en"
        private const val LANG_ZH = "zh"

        /** Máy không có giọng tiếng Việt nào dùng được: thử lại cũng vô ích, phải báo người dùng. */
        const val ERROR_NO_VOICE = -1001
    }
}

internal fun Voice.toNativeInfo() = NativeVoiceInfo(
    name = name,
    language = NativeVoiceSelector.canonicalLanguage(locale.language.ifEmpty { locale.toLanguageTag() }),
    country = locale.country.uppercase(),
    installed = features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true,
    networkRequired = isNetworkConnectionRequired,
    quality = quality
)
