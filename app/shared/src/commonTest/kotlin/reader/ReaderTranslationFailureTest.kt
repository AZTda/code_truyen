package com.truyencloud.shared.reader

import com.truyencloud.shared.api.ApiHttpException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderTranslationFailureTest {

    // Giả lập tên lớp + thông báo thật của Ktor / OkHttp / Darwin
    private class UnknownHostException(message: String) : Exception(message)
    private class HttpRequestTimeoutException(message: String) : Exception(message)
    private class ConnectException(message: String) : Exception(message)

    private fun kind(e: Throwable) = ReaderTranslationFailure.classify(e)

    @Test
    fun loginRequiredFromServerDetailAndStatusText() {
        // detail thật của máy chủ khi khách gọi /v1/translate
        assertEquals(
            TranslationFailureKind.LOGIN_REQUIRED,
            kind(Exception("Yêu cầu đăng nhập. Token không hợp lệ hoặc đã hết hạn."))
        )
        assertEquals(TranslationFailureKind.LOGIN_REQUIRED, kind(Exception("Máy chủ trả về mã lỗi 401")))
        assertEquals(TranslationFailureKind.LOGIN_REQUIRED, kind(ApiHttpException(401, "Dịch thất bại (mã 401)")))
        assertEquals("Đăng nhập để dịch chương này bằng AI", ReaderTranslationFailure.messageFor(Exception("Máy chủ trả về mã lỗi 401")))
    }

    @Test
    fun serverBusyFor503AndAiBusyDetail() {
        assertEquals(
            TranslationFailureKind.SERVER_BUSY,
            kind(Exception("AI tạm thời chưa dịch được chương này. Vui lòng thử lại sau ít phút."))
        )
        assertEquals(TranslationFailureKind.SERVER_BUSY, kind(Exception("Lỗi dịch Antigravity CLI: worker exited")))
        assertEquals(TranslationFailureKind.SERVER_BUSY, kind(Exception("Máy chủ trả về mã lỗi 503")))
        assertEquals(TranslationFailureKind.SERVER_BUSY, kind(ApiHttpException(503, "Dịch thất bại (mã 503)")))
        assertEquals(TranslationFailureKind.SERVER_BUSY, kind(ApiHttpException(429, "Quá nhiều yêu cầu")))
        assertEquals("Máy chủ dịch đang bận", ReaderTranslationFailure.messageFor(ApiHttpException(503, "x")))
    }

    @Test
    fun timeoutCountsAsServerBusy() {
        assertEquals(
            TranslationFailureKind.SERVER_BUSY,
            kind(HttpRequestTimeoutException("Request timeout has expired [url=https://x/v1/translate, request_timeout=900000 ms]"))
        )
        assertEquals(TranslationFailureKind.SERVER_BUSY, kind(ConnectException("Connection refused")))
    }

    @Test
    fun noNetwork() {
        assertEquals(
            TranslationFailureKind.NO_NETWORK,
            kind(UnknownHostException("Unable to resolve host \"truyen.achatvn.com\": No address associated with hostname"))
        )
        assertEquals(TranslationFailureKind.NO_NETWORK, kind(Exception("Network is unreachable")))
        assertEquals("Không có mạng", ReaderTranslationFailure.messageFor(Exception("Network is unreachable")))
    }

    @Test
    fun unknownErrorsGetGenericNonTechnicalMessage() {
        val msg = ReaderTranslationFailure.messageFor(Exception("Không có nội dung gốc để dịch."))
        assertEquals(ReaderTranslationFailure.MSG_OTHER, msg)
        assertTrue(!msg.contains("Exception"))
    }

    @Test
    fun chapterNumbersInsideMessagesAreNotMistakenForStatusCodes() {
        // "Chương 503" không được hiểu là lỗi 503
        assertEquals(TranslationFailureKind.OTHER, kind(Exception("Không có nội dung gốc để dịch chương 503")))
        assertEquals(null, ReaderTranslationFailure.statusOf(Exception("Chương 503 rỗng")))
        assertEquals(404, ReaderTranslationFailure.statusOf(IllegalStateException("API error (404): {\"detail\":\"x\"}")))
    }

    @Test
    fun englishAdaptationHttpErrorsNeverLeakRawBodies() {
        // EnglishApi ném IllegalStateException("API error (<mã>): <thân phản hồi>"). englishNotice nay được hiện cho người đọc
        // (ReaderScreen), nên câu đó đi qua messageFor thay vì e.message thô.
        val unauthorised = IllegalStateException("API error (401): {\"detail\": \"Yêu cầu đăng nhập. Token không hợp lệ hoặc đã hết hạn.\"}")
        val missing = IllegalStateException("API error (404): {\"detail\": \"not found\"}")
        val busy = IllegalStateException("API error (503): {\"detail\": \"AI tạm thời chưa dịch được\"}")
        assertEquals(ReaderTranslationFailure.MSG_LOGIN_REQUIRED, ReaderTranslationFailure.messageFor(unauthorised))
        assertEquals(ReaderTranslationFailure.MSG_OTHER, ReaderTranslationFailure.messageFor(missing))
        assertEquals(ReaderTranslationFailure.MSG_SERVER_BUSY, ReaderTranslationFailure.messageFor(busy))
        listOf(unauthorised, missing, busy).forEach {
            val shown = ReaderTranslationFailure.messageFor(it)
            assertTrue("API error" !in shown && '{' !in shown, "thân phản hồi thô lọt ra giao diện: $shown")
        }
    }

    @Test
    fun chapterLoadMessages() {
        val notFound = ReaderTranslationFailure.chapterLoadMessage(IllegalStateException("API error (404): {}"), 12)
        assertTrue(notFound.contains("chương 12"))
        assertTrue(notFound.contains("chưa cập nhật"))

        val offline = ReaderTranslationFailure.chapterLoadMessage(UnknownHostException("Unable to resolve host"), 7)
        assertTrue(offline.startsWith("Không có mạng"))
        assertTrue(offline.contains("chương 7"))

        val busy = ReaderTranslationFailure.chapterLoadMessage(IllegalStateException("API error (502): bad gateway"), null)
        assertTrue(busy.contains("chương này"))
        assertTrue(busy.contains("bận"))

        val other = ReaderTranslationFailure.chapterLoadMessage(Exception("boom"), 3)
        assertEquals("Không tải được chương 3. Vui lòng thử lại.", other)
    }
}
