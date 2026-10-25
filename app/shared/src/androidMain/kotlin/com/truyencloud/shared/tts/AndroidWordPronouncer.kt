package com.truyencloud.shared.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class AndroidWordPronouncer(private val context: Context) {
    private companion object {
        const val TAG = "AndroidWordPronouncer"
    }

    private var wordTts: TextToSpeech? = null
    private var isWordTtsReady: Boolean = false
    private val pendingWord = AtomicReference<Pair<String, String>?>(null)

    private fun ensureWordTts() {
        if (wordTts != null) return
        wordTts = TextToSpeech(context.applicationContext) { status ->
            isWordTtsReady = status == TextToSpeech.SUCCESS
            if (isWordTtsReady) {
                pendingWord.getAndSet(null)?.let { (w, lang) -> speakWordInternal(w, lang) }
            }
        }
    }

    private fun speakWordInternal(word: String, language: String) {
        val engine = wordTts ?: return
        try {
            val wantEnglish = language.startsWith("en", ignoreCase = true)
            // Không có giọng đúng ngôn ngữ thì im lặng, không đọc từ Anh bằng giọng Việt (dạy sai cách phát âm)
            if (!selectVoice(engine, if (wantEnglish) "en" else "vi")) {
                Log.w(TAG, "Không có giọng '$language' để phát âm từ '$word'")
                return
            }
            engine.setSpeechRate(0.9f)
            engine.setPitch(1.0f)
            engine.speak(word, TextToSpeech.QUEUE_FLUSH, null, "word_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error pronouncing word $word: ${e.message}")
        }
    }

    /**
     * Giọng đã tải, đúng ngôn ngữ, ưu tiên en-US và giọng cục bộ ([NativeVoiceSelector]); kiểm tra kết quả `setVoice` /
     * `setLanguage` vì bộ máy giữ giọng cũ khi nạp lỗi.
     */
    private fun selectVoice(engine: TextToSpeech, lang: String): Boolean {
        val voices: Set<Voice> = engine.voices.orEmpty()
        val byName = voices.associateBy { it.name }
        for (info in NativeVoiceSelector.rank(voices.map { it.toNativeInfo() }, lang, null)) {
            val voice = byName[info.name] ?: continue
            if (engine.setVoice(voice) == TextToSpeech.SUCCESS) return true
        }
        val locales = if (lang == "en") listOf(Locale.US, Locale.ENGLISH) else listOf(Locale.forLanguageTag("vi-VN"))
        return locales.any { engine.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE }
    }

    fun speakWord(word: String, language: String) {
        val clean = word.trim()
        if (clean.isBlank()) return
        ensureWordTts()
        if (isWordTtsReady) {
            speakWordInternal(clean, language)
        } else {
            pendingWord.set(clean to language)
        }
    }

    fun shutdown() {
        try {
            wordTts?.stop()
            wordTts?.shutdown()
        } catch (_: Exception) {
        } finally {
            wordTts = null
            isWordTtsReady = false
        }
    }
}
