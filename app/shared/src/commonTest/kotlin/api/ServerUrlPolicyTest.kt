package com.truyencloud.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerUrlPolicyTest {
    private val android = CleartextPolicies.androidNetworkSecurityConfig
    private val ios = CleartextPolicies.iosLocalNetworking

    private fun valid(input: String, policy: CleartextPolicy = ios): ServerUrlCheck.Valid =
        assertIs(ServerUrlValidator.check(input, policy), "'$input' phải hợp lệ")

    private fun invalid(input: String, policy: CleartextPolicy = ios): ServerUrlCheck.Invalid =
        assertIs(ServerUrlValidator.check(input, policy), "'$input' phải bị từ chối")

    private inline fun <reified T> assertIs(value: Any?, message: String): T {
        assertTrue(value is T, "$message nhưng nhận $value")
        return value as T
    }

    @Test
    fun httpsIsAcceptedWithoutWarning() {
        val ok = valid("https://truyen.achatvn.com")
        assertEquals("https://truyen.achatvn.com", ok.url)
        assertNull(ok.warning)
    }

    @Test
    fun normalizesCaseWhitespaceAndTrailingSlash() {
        assertEquals("https://truyen.achatvn.com", valid("  HTTPS://Truyen.AchatVN.com/  ").url)
        assertEquals("https://truyen.achatvn.com/api", valid("https://truyen.achatvn.com/api///").url)
    }

    @Test
    fun missingSchemeDefaultsToHttps() {
        assertEquals("https://truyen.achatvn.com", valid("truyen.achatvn.com").url)
        assertEquals("https://example.com:8443", valid("example.com:8443").url)
    }

    @Test
    fun httpToPublicHostIsRejected() {
        val bad = invalid("http://truyen.achatvn.com")
        assertTrue(bad.reason.contains("HTTPS"))
        invalid("http://8.8.8.8:8000")
    }

    @Test
    fun httpToPrivateLanAllowedOnIosWithWarning() {
        val ok = valid("http://192.168.1.10:8000")
        assertEquals("http://192.168.1.10:8000", ok.url)
        assertNotNull(ok.warning)
        assertTrue(ok.warning!!.contains("HTTP"))
        valid("http://10.0.0.5:8000")
        valid("http://172.20.1.1")
        valid("http://minipc.local:8000")
        valid("http://minipc:8000")
        valid("http://localhost:8000")
    }

    @Test
    fun httpToPrivateLanBlockedOnAndroidButExplainsWhy() {
        val bad = invalid("http://192.168.1.10:8000", android)
        assertTrue(bad.reason.contains("LAN"))
        assertTrue(bad.reason.contains("HTTPS"))
    }

    @Test
    fun androidAllowsLocalhostAndEmulatorAlias() {
        assertNotNull(valid("http://10.0.2.2:8000", android).warning)
        assertNotNull(valid("http://localhost:8000", android).warning)
        assertNotNull(valid("http://127.0.0.1:8000", android).warning)
    }

    @Test
    fun publicIpOverHttpsIsFine() {
        assertNull(valid("https://203.0.113.7:8443").warning)
    }

    @Test
    fun rejectsGarbage() {
        invalid("")
        invalid("   ")
        invalid("ftp://truyen.achatvn.com")
        invalid("javascript:alert(1)")
        invalid("https://")
        invalid("https://truyen .achatvn.com")
        invalid("https://user:pass@truyen.achatvn.com")
        invalid("https://truyen.achatvn.com?x=1")
        invalid("https://truyen.achatvn.com/#frag")
        invalid("https://truyen.achatvn.com:99999")
        invalid("https://truyen.achatvn.com:abc")
        invalid("https://-bad-.com")
        invalid("https://a..b.com")
        invalid("https://truyen.achatvn.com/../x")
    }

    @Test
    fun parsesPortsAndPaths() {
        val p = ServerUrlValidator.parse("http://Host.Example:8080/base/")!!
        assertEquals("http", p.scheme)
        assertEquals("host.example", p.host)
        assertEquals(8080, p.port)
        assertEquals("/base", p.path)
        assertEquals(443, ServerUrlValidator.parse("https://x.com")!!.port)
        assertEquals(80, ServerUrlValidator.parse("http://x.com")!!.port)
        assertNull(ServerUrlValidator.parse("x.com"))
    }

    @Test
    fun ipv6Literals() {
        assertEquals("::1", ServerUrlValidator.parse("http://[::1]:8000")!!.host)
        assertTrue(ServerUrlValidator.isLocalOrPrivateHost("::1"))
        assertFalse(ServerUrlValidator.isLocalOrPrivateHost("2001:db8::1"))
    }

    @Test
    fun privateHostClassification() {
        listOf("localhost", "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.1.1", "nas.local", "minipc")
            .forEach { assertTrue(ServerUrlValidator.isLocalOrPrivateHost(it), it) }
        listOf("172.15.0.1", "172.32.0.1", "8.8.8.8", "example.com", "192.169.0.1", "1.2.3.4.nip.io")
            .forEach { assertFalse(ServerUrlValidator.isLocalOrPrivateHost(it), it) }
    }

    @Test
    fun defaultMediaFollowsBaseUrl() {
        assertEquals("https://truyen.achatvn.com/static/user_avatar_v2.jpg", ServerUrls.defaultAvatar(ServerUrls.DEFAULT))
        assertEquals("http://10.0.2.2:8000/static/user_banner_v2.jpg", ServerUrls.defaultBanner("http://10.0.2.2:8000/"))
    }

    @Test
    fun recognisesDefaultMediaOfAnyServer() {
        assertTrue(ServerUrls.isDefaultAvatar(""))
        assertTrue(ServerUrls.isDefaultAvatar("https://truyen.achatvn.com/static/user_avatar_v2.jpg"))
        assertTrue(ServerUrls.isDefaultAvatar("http://10.0.2.2:8000/static/user_avatar_v2.jpg"))
        assertFalse(ServerUrls.isDefaultAvatar("https://cdn.example.com/me.png"))
        assertTrue(ServerUrls.isDefaultBanner("http://x/static/user_banner_v2.jpg"))
    }

    @Test
    fun resolvesServerRelativeMedia() {
        assertEquals("https://h.com/static/a.jpg", ServerUrls.resolveMedia("/static/a.jpg", "https://h.com/"))
        assertEquals("https://cdn.com/a.jpg", ServerUrls.resolveMedia("https://cdn.com/a.jpg", "https://h.com"))
        assertEquals("//cdn.com/a.jpg", ServerUrls.resolveMedia("//cdn.com/a.jpg", "https://h.com"))
        assertEquals("", ServerUrls.resolveMedia("", "https://h.com"))
    }

    @Test
    fun sameOriginComparison() {
        assertTrue(ServerUrls.sameOrigin("https://truyen.achatvn.com", "https://TRUYEN.achatvn.com/"))
        assertTrue(ServerUrls.sameOrigin("https://x.com", "https://x.com:443"))
        assertFalse(ServerUrls.sameOrigin("https://x.com", "http://x.com"))
        assertFalse(ServerUrls.sameOrigin("https://x.com", "https://y.com"))
        assertFalse(ServerUrls.sameOrigin("http://10.0.0.1:8000", "http://10.0.0.1:8001"))
    }
}
