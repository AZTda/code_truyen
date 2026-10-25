package com.truyencloud.shared.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatePolicyTest {

    @Test
    fun platformApiNames() {
        assertEquals("android", UpdatePlatform.ANDROID.apiName)
        assertEquals("ios", UpdatePlatform.IOS.apiName)
    }

    @Test
    fun otaOnlyOnAndroid() {
        assertTrue(UpdatePolicy.supportsOta(UpdatePlatform.ANDROID))
        assertFalse(UpdatePolicy.supportsOta(UpdatePlatform.IOS))
    }

    @Test
    fun iosNeverOffersAnUpdateEvenWhenMandatory() {
        val decision = UpdatePolicy.decide(UpdatePlatform.IOS, 1, 99, serverSaysMandatory = true, minVersionCode = 50)
        assertEquals(UpdateDecision.Unsupported, decision)
    }

    @Test
    fun androidNewerVersionIsAvailable() {
        assertEquals(UpdateDecision.Available(false), UpdatePolicy.decide(UpdatePlatform.ANDROID, 2, 3, false))
    }

    @Test
    fun androidSameOrOlderIsUpToDate() {
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(UpdatePlatform.ANDROID, 3, 3, false))
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(UpdatePlatform.ANDROID, 4, 3, true))
    }

    @Test
    fun mandatoryWhenServerSaysSo() {
        assertEquals(UpdateDecision.Available(true), UpdatePolicy.decide(UpdatePlatform.ANDROID, 2, 3, true))
    }

    @Test
    fun mandatoryWhenBelowMinimumVersion() {
        assertEquals(UpdateDecision.Available(true), UpdatePolicy.decide(UpdatePlatform.ANDROID, 2, 5, false, minVersionCode = 3))
        assertEquals(UpdateDecision.Available(false), UpdatePolicy.decide(UpdatePlatform.ANDROID, 3, 5, false, minVersionCode = 3))
    }

    @Test
    fun parsesBundleVersion() {
        assertEquals(12, UpdatePolicy.parseBundleVersion("12"))
        assertEquals(12, UpdatePolicy.parseBundleVersion(" 12.3.4 "))
        assertEquals(1, UpdatePolicy.parseBundleVersion(null))
        assertEquals(1, UpdatePolicy.parseBundleVersion("abc"))
        assertEquals(1, UpdatePolicy.parseBundleVersion("0"))
        assertEquals(1, UpdatePolicy.parseBundleVersion("-4"))
    }
}

class UpdateUrlPolicyTest {
    private val base = "https://truyen.achatvn.com"

    @Test
    fun relativeStaticPathsResolveAgainstBase() {
        assertEquals("$base/static/app.apk", UpdateUrlPolicy.resolve(base, "/static/app.apk"))
        assertEquals("$base/static/app.apk", UpdateUrlPolicy.resolve("$base/", "static/app.apk"))
        assertEquals("$base/static/app.apk?v=2", UpdateUrlPolicy.resolve(base, "/static/app.apk?v=2"))
    }

    @Test
    fun relativePathsOutsideStaticAreRejected() {
        assertNull(UpdateUrlPolicy.resolve(base, "/v1/app/download-latest"))
        assertNull(UpdateUrlPolicy.resolve(base, "//evil.example/static/app.apk"))
        assertNull(UpdateUrlPolicy.resolve(base, "/static/../etc/passwd"))
    }

    @Test
    fun sameHostAbsoluteUrlIsAccepted() {
        val url = "https://truyen.achatvn.com/static/truyencloud-latest.apk"
        assertEquals(url, UpdateUrlPolicy.resolve(base, url))
        assertEquals(
            "HTTPS://TRUYEN.ACHATVN.COM/static/x.apk",
            UpdateUrlPolicy.resolve(base, "HTTPS://TRUYEN.ACHATVN.COM/static/x.apk")
        )
    }

    @Test
    fun otherHostsAreRejected() {
        assertNull(UpdateUrlPolicy.resolve(base, "https://evil.example/static/app.apk"))
        assertNull(UpdateUrlPolicy.resolve(base, "https://truyen.achatvn.com.evil.example/app.apk"))
        assertNull(UpdateUrlPolicy.resolve(base, "https://truyen.achatvn.com@evil.example/app.apk"))
    }

    @Test
    fun nonHttpSchemesAreRejected() {
        assertNull(UpdateUrlPolicy.resolve(base, "file:///sdcard/app.apk"))
        assertNull(UpdateUrlPolicy.resolve(base, "content://media/app.apk"))
        assertNull(UpdateUrlPolicy.resolve(base, "ftp://truyen.achatvn.com/app.apk"))
        assertNull(UpdateUrlPolicy.resolve(base, "javascript:alert(1)"))
    }

    @Test
    fun httpsServerCannotBeDowngradedToHttp() {
        assertNull(UpdateUrlPolicy.resolve(base, "http://truyen.achatvn.com/static/app.apk"))
    }

    @Test
    fun lanServerOverHttpStillWorksForItsOwnHost() {
        val lan = "http://192.168.1.10:8000"
        assertEquals("$lan/static/app.apk", UpdateUrlPolicy.resolve(lan, "/static/app.apk"))
        assertEquals(
            "http://192.168.1.10:8000/static/app.apk",
            UpdateUrlPolicy.resolve(lan, "http://192.168.1.10:8000/static/app.apk")
        )
        assertNull(UpdateUrlPolicy.resolve(lan, "http://192.168.1.11:8000/static/app.apk"))
    }

    @Test
    fun blankOrWeirdInputIsRejected() {
        assertNull(UpdateUrlPolicy.resolve(base, ""))
        assertNull(UpdateUrlPolicy.resolve(base, "   "))
        assertNull(UpdateUrlPolicy.resolve(base, "https://truyen.achatvn.com/static/a b.apk"))
        assertNull(UpdateUrlPolicy.resolve(base, "https://truyen.achatvn.com\\static\\a.apk"))
        assertNull(UpdateUrlPolicy.resolve("not a url", "/static/app.apk"))
    }
}
