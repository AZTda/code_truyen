package com.truyencloud.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SyncAccountTest {
    // {"sub":"user_42","usr":"an","iat":1,"exp":2} dạng base64url không đệm, như máy chủ cấp.
    private val payload = "eyJzdWIiOiJ1c2VyXzQyIiwidXNyIjoiYW4iLCJpYXQiOjEsImV4cCI6Mn0"

    @Test
    fun blankTokenIsGuest() {
        assertEquals(SyncAccount.GUEST, SyncAccount.keyFor(""))
        assertEquals(SyncAccount.GUEST, SyncAccount.keyFor("   "))
    }

    @Test
    fun keyIsTheUserIdFromThePayloadAndSurvivesRelogin() {
        assertEquals("user_42", SyncAccount.keyFor("$payload.signatureOne"))
        assertEquals("user_42", SyncAccount.keyFor("$payload.anotherSignature"))
    }

    @Test
    fun malformedTokenStillGetsAStableKey() {
        val a = SyncAccount.keyFor("not-a-jwt")
        assertEquals(a, SyncAccount.keyFor("not-a-jwt"))
        assertNotEquals(a, SyncAccount.keyFor("another-opaque-token"))
    }

    @Test
    fun base64UrlDecoderHandlesPaddingAndRejectsGarbage() {
        assertEquals("hello", SyncAccount.decodeBase64Url("aGVsbG8")?.decodeToString())
        assertEquals("hello", SyncAccount.decodeBase64Url("aGVsbG8=")?.decodeToString())
        assertNull(SyncAccount.decodeBase64Url("***"))
        assertNull(SyncAccount.decodeBase64Url(""))
    }
}
