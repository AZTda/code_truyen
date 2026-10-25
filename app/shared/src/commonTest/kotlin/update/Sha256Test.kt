package com.truyencloud.shared.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Sha256Test {

    private fun hex(text: String) = Sha256.hex(text.encodeToByteArray())

    @Test
    fun fipsVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")
        )
        assertEquals(
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            hex(
                "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopq" +
                    "klmnopqrlmnopqrsmnopqrstnopqrstu"
            )
        )
        assertEquals(
            "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
            hex("The quick brown fox jumps over the lazy dog")
        )
    }

    @Test
    fun paddingBoundaries() {
        // 55/56/57 và 63/64/65 byte: các ranh giới mà phần đệm + độ dài 64-bit dễ sai nhất
        val expected = mapOf(
            1 to "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
            55 to "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
            56 to "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
            57 to "f13b2d724659eb3bf47f2dd6af1accc87b81f09f59f2b75e5c0bed6589dfe8c6",
            63 to "7d3e74a05d7db15bce4ad9ec0658ea98e3f06eeecf16b4c6fff2da457ddc2f34",
            64 to "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
            65 to "635361c48bb9eab14198e76ea8ab7f1a41685d6ad62aa9146d301d4f17eb0ae0",
            119 to "31eba51c313a5c08226adf18d4a359cfdfd8d2e816b13f4af952f7ea6584dcfb",
            120 to "2f3d335432c70b580af0e8e1b3674a7c020d683aa5f73aaaedfdc55af904c21c",
            128 to "6836cf13bac400e9105071cd6af47084dfacad4e5e302c94bfed24e013afb73e",
            1000 to "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3"
        )
        for ((length, digest) in expected) {
            assertEquals(digest, Sha256.hex(ByteArray(length) { 'a'.code.toByte() }), "độ dài $length")
        }
    }

    @Test
    fun multiByteUtf8() {
        assertEquals(
            "af054076d08ffc46cd7a1af7025562af5838eba754b9ede221161be91a9626f1",
            hex("Tiếng Việt: đăng nhập 剑来")
        )
    }

    @Test
    fun millionAs() {
        val hash = Sha256()
        val chunk = ByteArray(1000) { 'a'.code.toByte() }
        repeat(1000) { hash.update(chunk) }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", hash.digestHex())
    }

    @Test
    fun chunkingDoesNotChangeResult() {
        val data = ByteArray(5000) { (it * 31 + 7).toByte() }
        val whole = Sha256.hex(data)

        val byteByByte = Sha256().apply { data.forEach { update(byteArrayOf(it)) } }.digestHex()
        assertEquals(whole, byteByByte)

        // Kích thước khúc bất thường, giống vòng đọc 32 KB của trình tải nhưng lệch khối 64 byte
        val odd = Sha256().apply {
            var pos = 0
            var size = 1
            while (pos < data.size) {
                val take = minOf(size, data.size - pos)
                update(data, pos, take)
                pos += take
                size = size * 3 % 251 + 1
            }
        }.digestHex()
        assertEquals(whole, odd)
    }

    @Test
    fun rejectsReuseAfterDigest() {
        val hash = Sha256()
        hash.update("abc".encodeToByteArray())
        hash.digest()
        assertFailsWith<IllegalStateException> { hash.update(byteArrayOf(1)) }
        assertFailsWith<IllegalStateException> { hash.digest() }
    }

    @Test
    fun rejectsBadRange() {
        assertFailsWith<IllegalArgumentException> { Sha256().update(ByteArray(4), 2, 5) }
    }
}
