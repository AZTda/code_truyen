package com.truyencloud.shared.tts

import com.truyencloud.shared.api.TruyenCloudApi
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Tải âm thanh giọng AI của từng mảnh về tệp trên máy rồi mới phát (Android MediaPlayer / iOS AVPlayer phát tệp cục bộ).
 *
 * Vì sao không để trình phát tự mở URL: thời gian chờ của trình phát không chỉnh được (máy chủ có thể cần 10-20 giây để
 * dựng tại chỗ), trình phát không cho biết mã lỗi (503 "chưa sẵn sàng" hay 422 "tiếng Trung"), và muốn nghe tiếp đúng chỗ
 * sau khi hệ thống thu hồi trình phát thì phải mở lại được CHÍNH tệp đó rồi tua.
 *
 * Cùng một khoá đang tải thì lượt gọi sau chờ chung lượt trước (tải sẵn mảnh kế + lúc tới lượt phát không tải hai lần).
 */
class TtsAudioFetcher(
    private val transport: TtsAudioTransport,
    private val files: TtsAudioFileStore,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = TtsAudioPolicy.STREAM_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val lock = Mutex()
    private val inFlight = HashMap<String, CompletableDeferred<TtsAudioFetch>>()

    /** Tệp đã có trên máy (không gọi mạng) hoặc null. */
    fun cached(key: String): TtsAudioFetch.Ready? {
        val size = files.sizeOf(key)
        return if (size > 0L) TtsAudioFetch.Ready(files.pathFor(key), TtsTextTiming.mp3DurationMs(size)) else null
    }

    suspend fun fetch(url: String, key: String): TtsAudioFetch {
        cached(key)?.let { return it }
        var owner = false
        val pending = lock.withLock {
            inFlight[key] ?: CompletableDeferred<TtsAudioFetch>().also { inFlight[key] = it; owner = true }
        }
        if (!owner) {
            val shared = pending.await()
            // Lượt tải chung bị huỷ (đổi giọng, rời màn đọc...) không phải lỗi máy chủ: tự tải lại
            return if (shared === CANCELLED) fetch(url, key) else shared
        }
        val result = try {
            // Ghi tệp không được chạy trên luồng giao diện
            withContext(ioDispatcher) { download(url, key) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                lock.withLock { inFlight.remove(key) }
                pending.complete(CANCELLED)
            }
            throw e
        }
        withContext(NonCancellable) {
            lock.withLock { inFlight.remove(key) }
            pending.complete(result)
        }
        return result
    }

    /** Tải sẵn (mảnh sắp tới) — không chờ, lỗi im lặng; tới lượt phát sẽ thử lại nếu cần. */
    fun prefetch(url: String, key: String): Job = scope.launch { runCatching { fetch(url, key) } }

    private suspend fun download(url: String, key: String): TtsAudioFetch {
        val response = try {
            transport.get(url, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return TtsAudioFetch.Failed(e.message ?: "Lỗi mạng")
        }
        TtsAudioPolicy.classify(response)?.let { return it }
        if (!files.write(key, response.body)) return TtsAudioFetch.Failed("Không ghi được tệp âm thanh")
        files.trim(TtsAudioPolicy.CACHE_MAX_BYTES)
        return TtsAudioFetch.Ready(files.pathFor(key), TtsAudioPolicy.durationMs(response))
    }

    private companion object {
        val CANCELLED = TtsAudioFetch.Failed("cancelled")
    }
}

/** Lớp mạng thật: client Ktor dùng chung (tự kèm token), thời gian chờ riêng dài hơn mặc định 45 giây. */
class KtorTtsAudioTransport(private val client: () -> HttpClient = { TruyenCloudApi.httpClient }) : TtsAudioTransport {
    override suspend fun get(url: String, timeoutMs: Long): TtsHttpResponse {
        val response = client().get(url) {
            timeout {
                requestTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
        val headers = HashMap<String, String>()
        for (name in listOf("X-Audio-Duration-Ms", "Retry-After", "X-Local-Tts-Reason", "Content-Type")) {
            response.headers[name]?.let { headers[name.lowercase()] = it }
        }
        return TtsHttpResponse(response.status.value, headers, response.readRawBytes())
    }
}
