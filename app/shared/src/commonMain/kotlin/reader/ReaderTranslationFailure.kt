package com.truyencloud.shared.reader

import com.truyencloud.shared.api.ApiHttpException
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.api.ServerErrorException
import com.truyencloud.shared.repo.ApiErrors

/** Nhóm lỗi khi dịch chương — quyết định câu báo hiện cho người đọc. */
enum class TranslationFailureKind { LOGIN_REQUIRED, SERVER_BUSY, NO_NETWORK, OTHER }

/**
 * Đổi lỗi kỹ thuật khi dịch / tải chương thành câu tiếng Việt thân thiện.
 *
 * `TranslateApi` và `ChapterApi` ném `Exception` thuần chứa câu `detail` của máy chủ (không kèm mã HTTP),
 * nên ngoài [ApiHttpException] / [ServerErrorException] còn phải nhận dạng theo nội dung thông báo.
 */
object ReaderTranslationFailure {
    const val MSG_LOGIN_REQUIRED = "Đăng nhập để dịch chương này bằng AI"
    const val MSG_SERVER_BUSY = "Máy chủ dịch đang bận"
    const val MSG_NO_NETWORK = "Không có mạng"
    const val MSG_OTHER = "Chưa dịch được chương này. Vui lòng thử lại"
    const val MSG_CANCELLED = "Đã hủy dịch chương này"

    /** "mã lỗi 503", "(503)", "HTTP 503", "status 503"... — chỉ khớp mã đứng sau một nhãn để khỏi nhầm số chương. */
    private val labelledStatus = Regex("""\b(?:mã lỗi|mã|http|status|code|error)\s*[:(]?\s*(\d{3})\b""", RegexOption.IGNORE_CASE)
    private val parenStatus = Regex("""\((\d{3})\)""")

    private val busyPhrases = listOf(
        "ai tạm thời", "thử lại sau ít phút", "lỗi dịch antigravity", "antigravity cli",
        "đang bận", "quá tải", "service unavailable", "không lấy lại được bản gốc"
    )

    fun classify(error: Throwable): TranslationFailureKind {
        if (ApiErrors.isAuthError(error)) return TranslationFailureKind.LOGIN_REQUIRED
        val status = statusOf(error)
        if (status != null && (status == 429 || status in 500..599)) return TranslationFailureKind.SERVER_BUSY
        val text = textOf(error)
        if (busyPhrases.any { it in text }) return TranslationFailureKind.SERVER_BUSY
        return when (FriendlyErrors.forNetworkText(text)) {
            FriendlyErrors.MSG_OFFLINE, FriendlyErrors.MSG_UNKNOWN_HOST, FriendlyErrors.MSG_INTERRUPTED ->
                TranslationFailureKind.NO_NETWORK
            // Quá hạn hoặc máy chủ từ chối kết nối: phía máy chủ có vấn đề chứ không phải người đọc mất mạng
            FriendlyErrors.MSG_TIMEOUT, FriendlyErrors.MSG_REFUSED -> TranslationFailureKind.SERVER_BUSY
            else -> TranslationFailureKind.OTHER
        }
    }

    fun message(kind: TranslationFailureKind): String = when (kind) {
        TranslationFailureKind.LOGIN_REQUIRED -> MSG_LOGIN_REQUIRED
        TranslationFailureKind.SERVER_BUSY -> MSG_SERVER_BUSY
        TranslationFailureKind.NO_NETWORK -> MSG_NO_NETWORK
        TranslationFailureKind.OTHER -> MSG_OTHER
    }

    fun messageFor(error: Throwable): String = message(classify(error))

    /** Mã HTTP nếu lỗi mang theo (ApiHttpException / ServerErrorException hoặc nằm trong câu thông báo). */
    fun statusOf(error: Throwable): Int? {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        chain.filterIsInstance<ApiHttpException>().firstOrNull()?.let { return it.statusCode }
        chain.filterIsInstance<ServerErrorException>().firstOrNull()?.let { return it.status }
        for (t in chain) {
            val m = t.message ?: continue
            val code = labelledStatus.find(m)?.groupValues?.get(1) ?: parenStatus.find(m)?.groupValues?.get(1)
            code?.toIntOrNull()?.takeIf { it in 100..599 }?.let { return it }
        }
        return null
    }

    /** Câu báo khi KHÔNG tải được nội dung chương (chưa tải về máy, máy chủ lỗi, vượt quá chương cuối...). */
    fun chapterLoadMessage(error: Throwable, chapterNumber: Int?): String {
        val label = if (chapterNumber != null && chapterNumber > 0) "chương $chapterNumber" else "chương này"
        if (ApiErrors.isNotFound(error)) {
            return "Không tìm thấy $label trên máy chủ — có thể truyện chưa cập nhật tới đây."
        }
        val status = statusOf(error)
        if (status != null && (status == 429 || status in 500..599)) {
            return "Máy chủ đang bận nên chưa tải được $label. Hãy thử lại sau ít phút."
        }
        return when (FriendlyErrors.forNetworkText(textOf(error))) {
            FriendlyErrors.MSG_OFFLINE, FriendlyErrors.MSG_UNKNOWN_HOST, FriendlyErrors.MSG_INTERRUPTED ->
                "Không có mạng và $label chưa được tải về máy."
            FriendlyErrors.MSG_TIMEOUT, FriendlyErrors.MSG_REFUSED ->
                "Máy chủ phản hồi quá chậm nên chưa tải được $label."
            else -> "Không tải được $label. Vui lòng thử lại."
        }
    }

    private fun textOf(error: Throwable): String = generateSequence(error) { it.cause }
        .take(6)
        .joinToString(" | ") { "${it::class.simpleName.orEmpty()} ${it.message.orEmpty()}" }
        .lowercase()
}
