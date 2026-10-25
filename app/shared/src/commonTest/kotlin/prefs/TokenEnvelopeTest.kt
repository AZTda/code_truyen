package com.truyencloud.shared.prefs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenEnvelopeTest {
    private val iv = ByteArray(12) { (it + 1).toByte() }
    private val cipherText = ByteArray(40) { (it * 7).toByte() }

    @Test
    fun roundTrip() {
        val encoded = TokenEnvelope.encode(iv, cipherText)
        val parts = assertNotNull(TokenEnvelope.decode(encoded))
        assertContentEquals(iv, parts.iv)
        assertContentEquals(cipherText, parts.cipherText)
    }

    @Test
    fun formatIsVersionedAndColonSeparated() {
        val encoded = TokenEnvelope.encode(iv, cipherText)
        assertTrue(encoded.startsWith("v1:"))
        assertEquals(3, encoded.split(':').size)
    }

    @Test
    fun encodedFormContainsNoPlaintext() {
        val secret = "eyJzdWIiOiJ1c2VyXzEifQ.c2lnbmF0dXJl"
        val encoded = TokenEnvelope.encode(iv, secret.encodeToByteArray().copyOf(32) + ByteArray(16))
        assertFalse(encoded.contains(secret))
    }

    @Test
    fun allByteValuesSurviveRoundTrip() {
        val everything = ByteArray(256) { it.toByte() }
        val parts = assertNotNull(TokenEnvelope.decode(TokenEnvelope.encode(iv, everything)))
        assertContentEquals(everything, parts.cipherText)
    }

    @Test
    fun rejectsMissingOrWrongShape() {
        assertNull(TokenEnvelope.decode(null))
        assertNull(TokenEnvelope.decode(""))
        assertNull(TokenEnvelope.decode("plain-token-without-envelope"))
        assertNull(TokenEnvelope.decode("v1:onlytwo"))
        assertNull(TokenEnvelope.decode("v1:a:b:c"))
    }

    @Test
    fun rejectsUnknownVersionInsteadOfGuessing() {
        val encoded = TokenEnvelope.encode(iv, cipherText)
        assertNull(TokenEnvelope.decode(encoded.replaceFirst("v1:", "v2:")))
        assertNull(TokenEnvelope.decode(encoded.replaceFirst("v1:", "V1:")))
    }

    @Test
    fun rejectsCorruptBase64() {
        assertNull(TokenEnvelope.decode("v1:@@@@:@@@@"))
    }

    @Test
    fun rejectsWrongIvOrTruncatedCipherText() {
        assertNull(TokenEnvelope.decode(TokenEnvelope.encode(ByteArray(8), cipherText)))
        // GCM cần ít nhất 16 byte thẻ xác thực + 1 byte dữ liệu
        assertNull(TokenEnvelope.decode(TokenEnvelope.encode(iv, ByteArray(16))))
        assertNull(TokenEnvelope.decode(TokenEnvelope.encode(iv, ByteArray(3))))
    }

    @Test
    fun emptyIvIsAProgrammingError() {
        assertFailsWith<IllegalArgumentException> { TokenEnvelope.encode(ByteArray(0), cipherText) }
    }
}
