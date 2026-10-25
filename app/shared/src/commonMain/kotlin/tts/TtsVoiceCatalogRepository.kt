package com.truyencloud.shared.tts

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.library.HomePrefsStore
import com.truyencloud.shared.library.defaultHomePrefs
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.TimeSource

/**
 * Tải danh mục giọng AI của MÁY CHỦ ĐANG DÙNG (`GET /v1/tts/voices`, khoá `catalog`) lúc chạy, để máy chủ đổi mô hình
 * (Nano <-> Turbo) không cần ra bản app mới. Bản tải được lưu theo TỪNG địa chỉ máy chủ trong cài đặt; chưa có thì dùng bản
 * Nano đi kèm ([VieNeuVoices]). Lỗi mạng / máy chủ đời trước (không có `catalog`) thì giữ danh mục đang có, im lặng.
 */
class TtsVoiceCatalogRepository(
    private val prefs: HomePrefsStore?,
    private val client: () -> HttpClient = { TruyenCloudApi.httpClient },
    private val nowMs: () -> Long = { clockOrigin.elapsedNow().inWholeMilliseconds }
) {
    private val lastRefreshAt = HashMap<String, Long>()

    /** Áp bản đã lưu của [serverUrl] (hoặc bản đi kèm nếu chưa có). true nếu có bản đã lưu. */
    fun applyCached(serverUrl: String): Boolean {
        val raw = runCatching { prefs?.getString(cacheKey(serverUrl)) }.getOrNull()
        val snapshot = raw?.let(::parse)
        if (snapshot == null) {
            TtsVoiceCatalog.resetToBundled()
            return false
        }
        TtsVoiceCatalog.update(snapshot)
        return true
    }

    /**
     * Tải lại danh mục của [serverUrl]; [force] = false thì bỏ qua nếu vừa tải trong [MIN_INTERVAL_MS] (mở trình đọc / Cài
     * đặt liên tục không gọi mạng liên tục). true nếu đã nhận danh mục mới.
     */
    suspend fun refresh(serverUrl: String, force: Boolean = false): Boolean {
        val url = serverUrl.trim().trimEnd('/')
        if (url.isBlank()) return false
        val now = nowMs()
        val last = lastRefreshAt[url]
        if (!force && last != null && now - last < MIN_INTERVAL_MS) return false
        lastRefreshAt[url] = now
        val body = try {
            val response = client().get("$url/v1/tts/voices")
            if (response.status.value != 200) return false
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        val snapshot = parse(body) ?: return false
        TtsVoiceCatalog.update(snapshot)
        runCatching { prefs?.putString(cacheKey(url), body) }
        return true
    }

    /** Tải lại ở nền; lỗi im lặng (không bao giờ chặn việc đọc / nghe). */
    fun refreshAsync(scope: CoroutineScope, serverUrl: String, force: Boolean = false) {
        scope.launch { runCatching { refresh(serverUrl, force) } }
    }

    companion object {
        const val CACHE_PREFIX = "tts_voice_catalog|"
        const val MIN_INTERVAL_MS = 60_000L
        private val clockOrigin = TimeSource.Monotonic.markNow()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Dùng chung cho cả ứng dụng (kho cài đặt thật của nền tảng). */
        val shared: TtsVoiceCatalogRepository by lazy { TtsVoiceCatalogRepository(defaultHomePrefs()) }

        fun cacheKey(serverUrl: String): String = CACHE_PREFIX + serverUrl.trim().trimEnd('/')

        /** Đọc phản hồi `/v1/tts/voices`; null nếu không có danh mục giọng thật (máy chủ đời trước) hoặc hỏng. */
        fun parse(body: String): TtsCatalogSnapshot? {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val catalog = root["catalog"] as? JsonArray ?: return null
            val voices = catalog.mapNotNull { (it as? JsonObject)?.toVoice() }
            if (voices.isEmpty()) return null
            val engine = root["engine"] as? JsonObject
            val defaults = root["default_voices"] as? JsonObject
            val female = defaults?.str("female") ?: root.str("default_voice") ?: ""
            val male = defaults?.str("male") ?: ""
            val legacy = (root["legacy_map"] as? JsonObject)?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }?.toMap().orEmpty()
            return TtsCatalogSnapshot(
                model = engine?.str("model") ?: VieNeuVoices.DEFAULT_MODE,
                voices = voices,
                femaleDefault = female,
                maleDefault = male,
                legacyMap = legacy,
                fromServer = true
            )
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun JsonObject.toVoice(): TtsVoiceProfile? {
            val id = str("id") ?: return null
            val name = str("name") ?: return null
            val gender = if (str("gender") == "Nam") "Nam" else "Nữ"
            val region = str("region")?.takeIf { it in REGIONS } ?: "Bắc"
            return vieNeuProfile(id, name, gender, region, str("style") ?: "tự nhiên", str("description"))
        }

        private val REGIONS = setOf("Bắc", "Trung", "Nam")
    }
}
