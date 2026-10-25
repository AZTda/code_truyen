package com.truyencloud.shared.tts

/**
 * Kết quả tải âm thanh giọng AI của MỘT mảnh từ `/v1/tts/stream` về tệp trên máy.
 *
 * Chỉ [Unavailable] và [Failed] là "giọng AI hỏng" (bật cầu dao, đọc bằng giọng của điện thoại). Máy chủ trả CHẬM (dựng
 * tại chỗ mất 10-20 giây) không phải lỗi: chờ tới [TtsAudioPolicy.STREAM_TIMEOUT_MS]. [Unsupported] (422: tiếng Trung,
 * đoạn quá dài) chỉ đọc RIÊNG mảnh đó bằng giọng máy, không đụng cầu dao.
 */
sealed class TtsAudioFetch {
    data class Ready(val path: String, val durationMs: Long) : TtsAudioFetch()
    data class Unavailable(val reason: String, val retryAfterSec: Int) : TtsAudioFetch()
    data class Unsupported(val detail: String) : TtsAudioFetch()
    data class Failed(val message: String) : TtsAudioFetch()

    /** Lỗi khiến phiên phải chuyển sang giọng máy (cầu dao). */
    val isServerFailure: Boolean get() = this is Unavailable || this is Failed
}

/** Phản hồi HTTP thô (đã đọc hết thân) mà [TtsAudioTransport] trả về; tên header viết thường. */
class TtsHttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/** Lớp mạng của [TtsAudioFetcher] (Ktor ở app thật, bản giả trong kiểm thử). Mất mạng / hết giờ thì ném lỗi. */
fun interface TtsAudioTransport {
    suspend fun get(url: String, timeoutMs: Long): TtsHttpResponse
}

/** Nơi lưu tệp âm thanh đã tải (thư mục cache của từng nền tảng). */
interface TtsAudioFileStore {
    fun pathFor(key: String): String
    fun sizeOf(key: String): Long
    fun write(key: String, bytes: ByteArray): Boolean

    /** Giữ thư mục dưới [maxBytes]: xoá tệp cũ nhất trước. */
    fun trim(maxBytes: Long) {}
}

object TtsAudioPolicy {
    /**
     * Thời gian chờ một mảnh: máy chủ chờ dựng tại chỗ tối đa TTS_ONDEMAND_TIMEOUT = 90 giây rồi mới trả 503, nên app chờ
     * 90 + 10 giây. Ngắn hơn thì một lần dựng chậm (máy chủ bận) bị coi là hỏng và cả phiên rơi xuống giọng máy oan.
     */
    const val STREAM_TIMEOUT_MS = 100_000L

    /** Tệp âm thanh giữ trên máy (~144 KB mỗi mảnh 250 chữ): đủ cho vài chương để nghe lại / nghe tiếp không tải lại. */
    const val CACHE_MAX_BYTES = 48L * 1024 * 1024

    /** Đọc phản hồi của `/v1/tts/stream` thành kết quả (chưa ghi tệp: [TtsAudioFetch.Ready] được dựng sau khi ghi). */
    fun classify(response: TtsHttpResponse): TtsAudioFetch? {
        val code = response.status
        return when {
            (code == 200 || code == 206) && response.body.isNotEmpty() -> null
            code == 200 || code == 206 -> TtsAudioFetch.Failed("Máy chủ trả âm thanh rỗng")
            code == 503 -> TtsAudioFetch.Unavailable(
                reason = response.headers["x-local-tts-reason"].orEmpty().ifBlank { "unavailable" },
                retryAfterSec = response.headers["retry-after"]?.trim()?.toIntOrNull() ?: 30
            )
            code == 422 -> TtsAudioFetch.Unsupported(detailOf(response.body.decodeToString()))
            else -> TtsAudioFetch.Failed("HTTP $code")
        }
    }

    /** `{"detail":"unsupported_language"}` -> "unsupported_language". */
    internal fun detailOf(body: String): String =
        Regex("\"detail\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: body.take(80)

    /** Độ dài tệp: header `X-Audio-Duration-Ms` của máy chủ, thiếu thì suy từ kích thước (MP3 CBR 64 kbps). */
    fun durationMs(response: TtsHttpResponse): Long =
        response.headers["x-audio-duration-ms"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            ?: TtsTextTiming.mp3DurationMs(response.body.size.toLong())

    /**
     * Khoá tệp trên máy cho (máy chủ, giọng, chữ): KHÔNG có tốc độ (trình phát áp tốc độ) nên đổi tốc độ không tải lại.
     * FNV-1a 64 bit + độ dài, đủ để không trùng trong vài trăm tệp của bộ đệm.
     */
    fun cacheKey(serverUrl: String, voiceId: String, text: String): String {
        var hash = -0x340d631b7bdddcdbL // 14695981039346656037
        for (ch in "$serverUrl|$voiceId|$text") {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return "v1_" + hash.toULong().toString(16) + "_" + text.length
    }
}
