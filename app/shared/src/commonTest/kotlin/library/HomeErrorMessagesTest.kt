package com.truyencloud.shared.library

import com.truyencloud.shared.api.ApiHttpException
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.api.UserFacingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Màn hình chính hiển thị lỗi qua FriendlyErrors. Các bài kiểm thử này ghim đúng những thông báo mà
 * NovelApi (tìm kiếm, Khám phá, cào link, nạp tệp) tạo ra để không rò rỉ chuỗi kỹ thuật ra giao diện.
 */
class HomeErrorMessagesTest {

    private class UnknownHostException(message: String) : Exception(message)
    private class HttpRequestTimeoutException(message: String) : Exception(message)

    @Test
    fun noNetworkAndTimeoutAreReadable() {
        val noNetwork = FriendlyErrors.of(UnknownHostException("Unable to resolve host \"x\": No address associated with hostname"))
        assertEquals(FriendlyErrors.MSG_UNKNOWN_HOST, noNetwork)
        assertFalse("resolve" in noNetwork)
        assertEquals(
            FriendlyErrors.MSG_TIMEOUT,
            FriendlyErrors.of(HttpRequestTimeoutException("Request timeout has expired [url=x, request_timeout=45000 ms]"))
        )
    }

    @Test
    fun rateLimitedSearchSaysTheServerIsBusy() {
        val msg = FriendlyErrors.of(ApiHttpException(429, "Tìm kiếm thất bại (mã 429): {\"detail\":\"Too many requests\"}"))
        assertTrue(msg.startsWith("Máy chủ đang bận"), msg)
        assertFalse("429" in msg && "{" in msg)
    }

    @Test
    fun expiredSessionIsExplainedNotShownAsHttp401() {
        assertEquals(
            FriendlyErrors.MSG_SESSION_EXPIRED,
            FriendlyErrors.of(ApiHttpException(401, "Lỗi cào dữ liệu (mã 401): {\"detail\":\"Invalid token\"}"))
        )
    }

    @Test
    fun serverErrorsOnDiscoverAndImportAreFriendly() {
        val discover = FriendlyErrors.of(ApiHttpException(503, "Tải danh sách khám phá thất bại (mã 503)"))
        assertTrue("bảo trì" in discover, discover)
        val import = FriendlyErrors.of(ApiHttpException(500, "Nạp tệp thất bại (mã 500): <html>Traceback</html>"))
        assertTrue(import.startsWith("Máy chủ đang gặp sự cố"), import)
        assertFalse("Traceback" in import)
    }

    @Test
    fun validationDetailFromTheServerIsShownWhenItIsShort() {
        val msg = FriendlyErrors.of(ApiHttpException(400, "Nạp tệp thất bại (mã 400): {\"detail\":\"Hệ thống chỉ hỗ trợ định dạng .txt hoặc .epub\"}"))
        assertEquals("Hệ thống chỉ hỗ trợ định dạng .txt hoặc .epub", msg)
    }

    @Test
    fun appGeneratedVietnameseMessagesPassThroughUnchanged() {
        assertEquals("Tệp rỗng.", FriendlyErrors.of(UserFacingException("Tệp rỗng.")))
    }
}
