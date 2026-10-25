package com.truyencloud.shared.tts

import android.content.Context
import android.util.Log
import com.truyencloud.shared.api.ServerUrls
import com.truyencloud.shared.api.TruyenCloudApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dựng URL luồng giọng đọc, tải âm thanh từng mảnh về tệp ([fetcher]) và gọi `/v1/tts/prefetch` để máy chủ dựng trước.
 * Tốc độ KHÔNG nằm trong URL hay khoá tệp: máy chủ luôn trả 1.0x, trình phát tự áp tốc độ.
 */
internal class AndroidTtsStreamClient(private val scope: CoroutineScope, context: Context) {

    var serverUrl: String = ServerUrls.DEFAULT

    private var prefetchKey: String? = null
    private var prefetchFrom: Int = -1

    private val fetcher = TtsAudioFetcher(KtorTtsAudioTransport(), AndroidTtsAudioFileStore(context), scope)

    fun resolveHostUrl(): String =
        if (serverUrl.contains("127.0.0.1") || serverUrl.contains("localhost")) {
            serverUrl.replace("127.0.0.1", "10.0.2.2").replace("localhost", "10.0.2.2")
        } else {
            serverUrl
        }

    fun buildStreamUrl(text: String, prefetchNextText: String?, profileId: String): String =
        TtsStreamUrl.build(resolveHostUrl(), text, prefetchNextText, profileId)

    private fun keyFor(text: String, profileId: String) = TtsAudioPolicy.cacheKey(resolveHostUrl(), profileId, text)

    /** Tải (hoặc lấy từ bộ đệm trên máy) âm thanh của [text]; chờ tối đa [TtsAudioPolicy.STREAM_TIMEOUT_MS]. */
    suspend fun fetch(text: String, profileId: String, prefetchNextText: String?): TtsAudioFetch =
        fetcher.fetch(buildStreamUrl(text, prefetchNextText, profileId), keyFor(text, profileId))

    fun prefetchAudio(text: String, profileId: String): Job =
        fetcher.prefetch(buildStreamUrl(text, null, profileId), keyFor(text, profileId))

    fun resetPrefetchWindow() {
        prefetchKey = null
        prefetchFrom = -1
    }

    /**
     * Nhờ máy chủ tổng hợp trước một cửa sổ [WINDOW] mảnh. Chỉ gửi lại khi đã đọc gần
     * hết cửa sổ trước (hoặc đổi giọng/chương) — trước đây gửi ở MỌI mảnh.
     */
    fun maybePrefetch(texts: List<String>, fromIndex: Int, profileId: String) {
        val key = "$profileId|${texts.size}|${texts.firstOrNull()?.hashCode()}"
        val windowStillAhead = key == prefetchKey && prefetchFrom >= 0 &&
            fromIndex >= prefetchFrom && fromIndex < prefetchFrom + REFILL_AFTER
        if (windowStillAhead) return
        prefetchKey = key
        prefetchFrom = fromIndex
        triggerServerPrefetch(texts, fromIndex, profileId)
    }

    private fun triggerServerPrefetch(texts: List<String>, fromIndex: Int, profileId: String) {
        if (fromIndex !in texts.indices) return
        // Mảnh tiếng Trung do giọng máy đọc: không bao giờ gửi lên máy chủ
        val slice = texts.subList(fromIndex, (fromIndex + WINDOW).coerceAtMost(texts.size))
            .map { it.trim() }
            .filter { SpeakableText.isSpeakable(it) && !TtsSegmentPlan.isDeviceOnly(it) }
        if (slice.isEmpty()) return
        slice.groupBy(TtsTextLanguage::langParam).forEach { (lang, group) -> postPrefetch(group, lang, profileId, fromIndex) }
    }

    private fun postPrefetch(slice: List<String>, lang: String, profileId: String, fromIndex: Int) {
        val body = JsonObject(
            mapOf(
                "texts" to JsonArray(slice.map { JsonPrimitive(it) }),
                "voice" to JsonPrimitive(profileId),
                "speed" to JsonPrimitive(1.0),
                "lang" to JsonPrimitive(lang)
            )
        ).toString()
        val token = TruyenCloudApi.authToken
        val endpoint = "${resolveHostUrl()}/v1/tts/prefetch"
        scope.launch(Dispatchers.IO) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3000
                    readTimeout = 4000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                Log.d(TAG, "Server prefetch ${slice.size} mảnh từ #$fromIndex, code=$code")
            } catch (e: Exception) {
                Log.w(TAG, "Server prefetch lỗi: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "AndroidTtsStreamClient"
        const val WINDOW = 12
        const val REFILL_AFTER = 8
    }
}
