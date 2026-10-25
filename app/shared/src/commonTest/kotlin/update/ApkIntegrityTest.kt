package com.truyencloud.shared.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApkIntegrityTest {
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun matchingHashAndSizePass() {
        assertEquals(ApkCheck.Ok, ApkIntegrity.verify(abcHash, 3, abcHash, 3))
    }

    @Test
    fun hashComparisonIgnoresCaseAndWhitespace() {
        assertEquals(ApkCheck.Ok, ApkIntegrity.verify("  ${abcHash.uppercase()}\n", 3, abcHash, 3))
    }

    @Test
    fun differentHashIsRejected() {
        val other = "0".repeat(64)
        assertEquals(ApkCheck.HashMismatch, ApkIntegrity.verify(abcHash, 3, other, 3))
        assertEquals(ApkIntegrity.HASH_MISMATCH_MESSAGE, ApkIntegrity.messageFor(ApkCheck.HashMismatch))
        assertEquals("Tệp cập nhật không khớp mã kiểm tra", ApkIntegrity.HASH_MISMATCH_MESSAGE)
    }

    @Test
    fun malformedManifestHashIsNotTrusted() {
        // Có mã nhưng sai định dạng: không được coi như "không có mã"
        assertEquals(ApkCheck.HashMismatch, ApkIntegrity.verify("not-a-hash", 0, abcHash, 3))
        assertEquals(ApkCheck.HashMismatch, ApkIntegrity.verify("abc", 0, abcHash, 3))
    }

    @Test
    fun missingManifestHashFallsBackToSizeOnly() {
        assertEquals(ApkCheck.Ok, ApkIntegrity.verify("", 3, "anything", 3))
        assertEquals(ApkCheck.Ok, ApkIntegrity.verify(null, 0, "anything", 12345))
    }

    @Test
    fun sizeMismatchIsReportedBeforeHash() {
        val check = ApkIntegrity.verify(abcHash, 10, abcHash, 3)
        assertEquals(ApkCheck.SizeMismatch(10, 3), check)
        assertTrue(ApkIntegrity.messageFor(check)!!.contains("dung lượng"))
    }

    @Test
    fun unknownManifestSizeIsNotChecked() {
        assertEquals(ApkCheck.Ok, ApkIntegrity.verify(abcHash, 0, abcHash, 999))
    }

    @Test
    fun okHasNoMessage() {
        assertNull(ApkIntegrity.messageFor(ApkCheck.Ok))
    }

    @Test
    fun endToEndWithStreamedHash() {
        val payload = ByteArray(10_000) { (it % 251).toByte() }
        val streamed = Sha256()
        payload.toList().chunked(777).forEach { streamed.update(it.toByteArray()) }
        val digest = streamed.digestHex()

        assertEquals(ApkCheck.Ok, ApkIntegrity.verify(Sha256.hex(payload), payload.size.toLong(), digest, payload.size.toLong()))

        val tampered = payload.copyOf().also { it[5000] = (it[5000] + 1).toByte() }
        assertEquals(
            ApkCheck.HashMismatch,
            ApkIntegrity.verify(Sha256.hex(payload), payload.size.toLong(), Sha256.hex(tampered), payload.size.toLong())
        )
    }
}
