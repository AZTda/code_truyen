package com.truyencloud.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FriendlyErrorsTest {

    // Giả lập tên lớp + thông báo thật của Android/OkHttp/Ktor/Darwin
    private class UnknownHostException(message: String) : Exception(message)
    private class SocketTimeoutException(message: String) : Exception(message)
    private class HttpRequestTimeoutException(message: String) : Exception(message)
    private class ConnectException(message: String) : Exception(message)
    private class SSLHandshakeException(message: String) : Exception(message)
    private class SerializationException(message: String) : Exception(message)
    private class DarwinHttpRequestException(message: String) : Exception(message)

    @Test
    fun unknownHost() {
        val e = UnknownHostException("Unable to resolve host \"truyen.achatvn.com\": No address associated with hostname")
        assertEquals(FriendlyErrors.MSG_UNKNOWN_HOST, FriendlyErrors.of(e))
    }

    @Test
    fun timeouts() {
        assertEquals(FriendlyErrors.MSG_TIMEOUT, FriendlyErrors.of(SocketTimeoutException("timeout")))
        assertEquals(
            FriendlyErrors.MSG_TIMEOUT,
            FriendlyErrors.of(HttpRequestTimeoutException("Request timeout has expired [url=x, request_timeout=45000 ms]"))
        )
        assertEquals(FriendlyErrors.MSG_TIMEOUT, FriendlyErrors.of(Exception("The request timed out.")))
    }

    @Test
    fun connectionRefusedAndOffline() {
        assertEquals(
            FriendlyErrors.MSG_REFUSED,
            FriendlyErrors.of(ConnectException("Failed to connect to /192.168.1.10:8000"))
        )
        assertEquals(FriendlyErrors.MSG_REFUSED, FriendlyErrors.of(Exception("Connection refused")))
        assertEquals(
            FriendlyErrors.MSG_OFFLINE,
            FriendlyErrors.of(Exception("Network is unreachable"))
        )
    }

    @Test
    fun iosUrlErrorCodes() {
        assertEquals(
            FriendlyErrors.MSG_OFFLINE,
            FriendlyErrors.of(DarwinHttpRequestException("The Internet connection appears to be offline. (NSURLErrorDomain error -1009.)"))
        )
        assertEquals(
            FriendlyErrors.MSG_TIMEOUT,
            FriendlyErrors.of(DarwinHttpRequestException("(NSURLErrorDomain error -1001.)"))
        )
        assertEquals(
            FriendlyErrors.MSG_UNKNOWN_HOST,
            FriendlyErrors.of(DarwinHttpRequestException("A server with the specified hostname could not be found."))
        )
    }

    @Test
    fun cleartextBlockedByAndroid() {
        val e = Exception("CLEARTEXT communication to 192.168.1.10 not permitted by network security policy")
        assertEquals(FriendlyErrors.MSG_CLEARTEXT, FriendlyErrors.of(e))
    }

    @Test
    fun tlsProblems() {
        assertEquals(
            FriendlyErrors.MSG_TLS,
            FriendlyErrors.of(SSLHandshakeException("Trust anchor for certification path not found."))
        )
    }

    @Test
    fun classLoaderErrorsAreNotMistakenForTls() {
        assertEquals(FriendlyErrors.MSG_GENERIC, FriendlyErrors.of(Exception("NoClassDefFoundError in classloader")))
    }

    @Test
    fun wrappedCauseIsInspected() {
        val wrapped = Exception("Network call failed", UnknownHostException("Unable to resolve host \"x\""))
        assertEquals(FriendlyErrors.MSG_UNKNOWN_HOST, FriendlyErrors.of(wrapped))
    }

    @Test
    fun badPayloadMeansWrongServer() {
        assertEquals(
            FriendlyErrors.MSG_BAD_PAYLOAD,
            FriendlyErrors.of(SerializationException("Unexpected JSON token at offset 0"))
        )
    }

    @Test
    fun unknownErrorNeverLeaksRawMessage() {
        val msg = FriendlyErrors.of(IllegalStateException("java.lang.NullPointerException at com.x.Y.z(Y.kt:12)"))
        assertEquals(FriendlyErrors.MSG_GENERIC, msg)
        assertFalse(msg.contains("NullPointer"))
    }

    @Test
    fun userFacingMessagePassesThrough() {
        assertEquals("Xin chào", FriendlyErrors.of(UserFacingException("Xin chào")))
    }

    @Test
    fun loginStatuses() {
        val ctx = ErrorContext.LOGIN
        assertEquals(
            "Tên đăng nhập hoặc mật khẩu không chính xác.",
            FriendlyErrors.of(ServerErrorException(401, "Tên đăng nhập hoặc mật khẩu không chính xác."), ctx)
        )
        assertEquals(
            "Tên đăng nhập hoặc mật khẩu không chính xác.",
            FriendlyErrors.of(ServerErrorException(401, null), ctx)
        )
        val limited = FriendlyErrors.of(ServerErrorException(429, "Bạn thao tác quá nhanh.", retryAfterSeconds = 240), ctx)
        assertTrue(limited.contains("quá nhiều lần"), limited)
        assertTrue(limited.contains("4 phút"), limited)
        assertTrue(FriendlyErrors.of(ServerErrorException(403, null), ctx).contains("quản trị viên"))
    }

    @Test
    fun sessionStatusesOutsideLogin() {
        assertEquals(FriendlyErrors.MSG_SESSION_EXPIRED, FriendlyErrors.of(ServerErrorException(401, "Yêu cầu đăng nhập."), ErrorContext.GENERIC))
        assertEquals(FriendlyErrors.MSG_ADMIN_ONLY, FriendlyErrors.of(ServerErrorException(403, "x"), ErrorContext.ADMIN_ACTION))
        assertEquals("Bạn không có quyền thực hiện thao tác này.", FriendlyErrors.of(ServerErrorException(403, null)))
    }

    @Test
    fun changePasswordValidationDetailIsShown() {
        assertEquals(
            "Mật khẩu hiện tại không chính xác.",
            FriendlyErrors.of(ServerErrorException(400, "Mật khẩu hiện tại không chính xác."), ErrorContext.CHANGE_PASSWORD)
        )
        assertEquals(
            "Thông tin gửi lên chưa hợp lệ. Vui lòng kiểm tra lại.",
            FriendlyErrors.of(ServerErrorException(400, "<html>oops</html>"), ErrorContext.CHANGE_PASSWORD)
        )
    }

    @Test
    fun serverErrors() {
        assertTrue(FriendlyErrors.of(ServerErrorException(500)).contains("sự cố"))
        assertTrue(FriendlyErrors.of(ServerErrorException(503)).contains("bảo trì"))
        assertTrue(FriendlyErrors.of(ServerErrorException(404)).contains("Không tìm thấy"))
        assertEquals(FriendlyErrors.MSG_TIMEOUT, FriendlyErrors.of(ServerErrorException(408)))
    }

    @Test
    fun waitTextRoundsUp() {
        assertEquals("1 giây", FriendlyErrors.waitText(0))
        assertEquals("45 giây", FriendlyErrors.waitText(45))
        assertEquals("1 phút", FriendlyErrors.waitText(60))
        assertEquals("5 phút", FriendlyErrors.waitText(241))
        assertEquals("2 giờ", FriendlyErrors.waitText(3601))
    }

    @Test
    fun parsesFastApiDetail() {
        assertEquals("Sai rồi", FriendlyErrors.parseDetail("""{"detail":"Sai rồi"}"""))
        assertEquals(
            "field required",
            FriendlyErrors.parseDetail("""{"detail":[{"loc":["body","x"],"msg":"field required","type":"missing"}]}""")
        )
        assertNull(FriendlyErrors.parseDetail(""))
        assertNull(FriendlyErrors.parseDetail("<html>502 Bad Gateway</html>"))
        assertNull(FriendlyErrors.parseDetail("""{"other":1}"""))
    }
}
