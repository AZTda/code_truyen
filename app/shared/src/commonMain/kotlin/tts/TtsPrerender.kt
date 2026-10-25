package com.truyencloud.shared.tts

import com.truyencloud.shared.api.TruyenCloudApi
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** Thân `POST /v1/tts/prerender` (khoá đúng tên của máy chủ). `count` = chương này và các chương liền sau. */
@Serializable
data class TtsPrerenderRequest(
    val novel_id: String,
    val chapter_number: Int,
    val count: Int = 1,
    val voice: String,
    /** null / "vi" = bản dịch Việt dùng chung, "en" = bản Anh, "original" = bản gốc. */
    val lang: String? = null,
    val priority: Int = 1,
    val from_segment: Int = 0
)

@Serializable
data class TtsPrerenderChapter(
    val chapter_number: Int = 0,
    val state: String = "",
    val total_segments: Int = 0,
    val ready_segments: Int = 0,
    val estimated_seconds_remaining: Int = 0,
    val lead_needed_segments: Int = 0,
    val segment_count: Int = 0,
    val device_segments: Int = 0,
    val start_segment: Int = 0,
    val ready_prefix_segments: Int = 0,
    val can_start: Boolean? = null
)

@Serializable
data class TtsPrerenderResponse(val status: String = "", val chapters: List<TtsPrerenderChapter> = emptyList())

/** Lớp mạng của lời gọi dựng trước (Ktor ở app thật, bản giả trong kiểm thử). null = lỗi / máy chủ đời trước. */
fun interface TtsPrerenderApi {
    suspend fun prerender(request: TtsPrerenderRequest): TtsPrerenderResponse?
}

/** Dùng client chung (tự kèm token nếu đã đăng nhập; khách vẫn gọi được, chỉ bị hạ ưu tiên). Lỗi nào cũng thành null. */
class KtorTtsPrerenderApi(
    private val baseUrl: () -> String = { TruyenCloudApi.baseUrl },
    private val client: () -> HttpClient = { TruyenCloudApi.httpClient }
) : TtsPrerenderApi {
    override suspend fun prerender(request: TtsPrerenderRequest): TtsPrerenderResponse? = try {
        val response = client().post("${baseUrl().trimEnd('/')}/v1/tts/prerender") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value == 200) response.body<TtsPrerenderResponse>() else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/**
 * Giọng AI của một chương đã dựng sẵn tới đâu (theo lần hỏi gần nhất), cho thanh nghe đọc và luật "chờ rồi tự phát".
 *
 * Bắt đầu phát được khi máy chủ báo `can_start` (đủ số mảnh đầu để nghe liền mạch tới hết chương), hoặc chương đã dựng xong,
 * hoặc không có gì để chờ (`no_text`: máy chủ không có đúng bản chữ này — bản dịch riêng sẽ dựng tại chỗ; `unavailable`:
 * giọng AI tắt / hỏng — luồng phát báo lỗi và chuyển sang giọng của điện thoại kèm thông báo).
 */
data class TtsReadiness(
    val novelId: String,
    val chapterNumber: Int,
    val voiceId: String,
    val state: String,
    val readySegments: Int,
    val totalSegments: Int,
    val leadNeeded: Int,
    val readyPrefix: Int,
    val segmentCount: Int,
    val estimatedSeconds: Int,
    val serverCanStart: Boolean?
) {
    val canStart: Boolean
        get() = when (state) {
            STATE_READY, STATE_NO_TEXT, STATE_UNAVAILABLE -> true
            else -> serverCanStart ?: (readyPrefix >= leadNeeded.coerceAtLeast(1))
        }

    /** Tiến độ tới lúc phát được (0..99) khi cần đệm nhiều mảnh; null khi chỉ chờ một mảnh (hiện số giây ước lượng). */
    val startPercent: Int?
        get() {
            val needed = leadNeeded.coerceAtLeast(1)
            if (needed <= 1) return null
            return (readyPrefix.coerceIn(0, needed) * 100 / needed).coerceAtMost(99)
        }

    /** Máy chủ cắt chương ra đúng số mảnh như app không (khác số mảnh = khác chữ, tệp dựng sẵn sẽ không trúng). */
    fun matches(textsCount: Int): Boolean = segmentCount <= 0 || segmentCount == textsCount

    /** Dòng trạng thái êm cho thanh nghe đọc lúc chờ. */
    val waitingLabel: String
        get() {
            val pct = startPercent
            return when {
                pct != null -> "Đang chuẩn bị giọng đọc… $pct%"
                estimatedSeconds in 1..600 -> "Đang chuẩn bị giọng đọc… ~${estimatedSeconds}s"
                else -> "Đang chuẩn bị giọng đọc…"
            }
        }

    companion object {
        const val STATE_READY = "ready"
        const val STATE_NO_TEXT = "no_text"
        const val STATE_UNAVAILABLE = "unavailable"

        fun from(novelId: String, voiceId: String, c: TtsPrerenderChapter) = TtsReadiness(
            novelId = novelId,
            chapterNumber = c.chapter_number,
            voiceId = voiceId,
            state = c.state,
            readySegments = c.ready_segments,
            totalSegments = c.total_segments,
            leadNeeded = c.lead_needed_segments,
            readyPrefix = c.ready_prefix_segments,
            segmentCount = c.segment_count,
            estimatedSeconds = c.estimated_seconds_remaining,
            serverCanStart = c.can_start
        )
    }
}

/** Luật bắt đầu phát giọng AI: chờ hay phát ngay. Thuần, để kiểm thử. */
object TtsStartGate {
    /** Hỏi lại mỗi chừng này khi đang chờ. */
    const val POLL_MS = 2_000L

    /** Hỏi hỏng liên tiếp chừng này lần thì thôi chờ: phát luôn, luồng phát tự xử lý lỗi (giọng của điện thoại + báo). */
    const val MAX_POLL_FAILURES = 3

    /** Chờ lâu hơn thế thì phát luôn: máy chủ dựng tại chỗ (ưu tiên cao nhất) mảnh đang cần. */
    const val MAX_WAIT_MS = 3 * 60 * 1000L

    /**
     * Có phải chờ trước khi phát không: chỉ khi đang chọn giọng AI, đã biết tiến độ của ĐÚNG chương / giọng này, chữ của
     * máy chủ khớp chữ của app (cùng số mảnh) và chưa đủ để nghe liền mạch.
     */
    fun mustWait(onlineMode: Boolean, readiness: TtsReadiness?, novelId: String, chapterNumber: Int, voiceId: String, textsCount: Int): Boolean {
        if (!onlineMode || readiness == null) return false
        if (readiness.novelId != novelId || readiness.chapterNumber != chapterNumber || readiness.voiceId != voiceId) return false
        return readiness.matches(textsCount) && !readiness.canStart
    }

    /** Sau một lần hỏi khi đang chờ: còn chờ tiếp không ([failures] = số lần hỏi hỏng liên tiếp, [waitedMs] = đã chờ). */
    fun keepWaiting(readiness: TtsReadiness?, failures: Int, waitedMs: Long, textsCount: Int): Boolean = when {
        waitedMs >= MAX_WAIT_MS -> false
        readiness == null -> failures < MAX_POLL_FAILURES
        !readiness.matches(textsCount) -> false
        else -> !readiness.canStart
    }
}
