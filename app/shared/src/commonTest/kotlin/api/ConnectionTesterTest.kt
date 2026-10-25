package com.truyencloud.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Kiểm tra kết nối": chỉ phần diễn giải thân phản hồi /v1/health là hàm thuần, phần mạng thì không kiểm thử ở đây. */
class ConnectionTesterTest {

    @Test
    fun healthyServerWithVersion() {
        val result = ConnectionTester.interpretHealthBody("""{"status":"healthy","version":"1.0.0"}""")
        assertTrue(result.ok)
        assertTrue("v1.0.0" in result.message, result.message)
    }

    @Test
    fun healthyServerWithoutVersionStillSucceeds() {
        val result = ConnectionTester.interpretHealthBody("""{"status":"healthy"}""")
        assertTrue(result.ok)
        assertEquals("Kết nối thành công.", result.message)
    }

    @Test
    fun blankVersionIsNotPrinted() {
        val result = ConnectionTester.interpretHealthBody("""{"status":"healthy","version":"  "}""")
        assertTrue(result.ok)
        assertFalse("máy chủ v" in result.message, result.message)
    }

    @Test
    fun otherStatusesMeanNotReady() {
        assertFalse(ConnectionTester.interpretHealthBody("""{"status":"degraded"}""").ok)
        assertFalse(ConnectionTester.interpretHealthBody("""{"status":"ok"}""").ok)
    }

    @Test
    fun aWebPageOrGarbageIsNotATruyenCloudServer() {
        // Địa chỉ gõ nhầm trỏ tới một trang web bất kỳ: không được báo "kết nối thành công"
        assertFalse(ConnectionTester.interpretHealthBody("<html><body>Welcome</body></html>").ok)
        assertFalse(ConnectionTester.interpretHealthBody("").ok)
        assertFalse(ConnectionTester.interpretHealthBody("[1,2,3]").ok)
        assertFalse(ConnectionTester.interpretHealthBody("null").ok)
    }

    @Test
    fun theMessageNeverEchoesTheRawBody() {
        val result = ConnectionTester.interpretHealthBody("<html>secret-internal-detail</html>")
        assertFalse("secret-internal-detail" in result.message)
    }
}
