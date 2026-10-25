package com.truyencloud.shared.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tải âm thanh giọng AI về tệp: chỉ 503 / lỗi mạng / hết giờ là "máy chủ hỏng" (bật cầu dao, đọc bằng giọng của điện thoại);
 * trả chậm không phải lỗi; 422 (tiếng Trung, đoạn quá dài) chỉ đổi giọng cho riêng mảnh đó. Khoá tệp không có tốc độ.
 */
class TtsAudioFetchTest {

    private class MemoryFiles : TtsAudioFileStore {
        val files = HashMap<String, ByteArray>()
        override fun pathFor(key: String) = "/cache/$key.mp3"
        override fun sizeOf(key: String) = files[key]?.size?.toLong() ?: 0L
        override fun write(key: String, bytes: ByteArray): Boolean { files[key] = bytes; return true }
    }

    private fun response(status: Int, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)) =
        TtsHttpResponse(status, headers, body)

    @Test
    fun phanLoaiPhanHoiCuaMayChu() {
        assertEquals(null, TtsAudioPolicy.classify(response(200, body = ByteArray(192))), "có âm thanh: ghi tệp")
        assertEquals(null, TtsAudioPolicy.classify(response(206, body = ByteArray(192))))
        val busy = TtsAudioPolicy.classify(response(503, mapOf("x-local-tts-reason" to "loading", "retry-after" to "12")))
        assertEquals(TtsAudioFetch.Unavailable("loading", 12), busy)
        assertTrue(busy!!.isServerFailure)
        val zh = TtsAudioPolicy.classify(response(422, body = """{"detail":"unsupported_language"}""".encodeToByteArray()))
        assertEquals(TtsAudioFetch.Unsupported("unsupported_language"), zh)
        assertFalse(zh!!.isServerFailure, "422: chỉ mảnh này đọc bằng giọng máy, không bật cầu dao")
        assertTrue(TtsAudioPolicy.classify(response(500))!!.isServerFailure)
        assertTrue(TtsAudioPolicy.classify(response(200))!!.isServerFailure, "200 rỗng là hỏng")
    }

    @Test
    fun doDaiLayTuHeaderThieuThiSuyTuKichThuoc() {
        assertEquals(17_990L, TtsAudioPolicy.durationMs(response(200, mapOf("x-audio-duration-ms" to "17990"), ByteArray(192))))
        assertEquals(24L * 10, TtsAudioPolicy.durationMs(response(200, body = ByteArray(1920))))
    }

    @Test
    fun khoaTepKhongCoTocDoNhungTheoMayChuGiongVaChu() {
        val a = TtsAudioPolicy.cacheKey("https://h", "my_duyen", "Xin chào.")
        assertEquals(a, TtsAudioPolicy.cacheKey("https://h", "my_duyen", "Xin chào."))
        assertTrue(a != TtsAudioPolicy.cacheKey("https://h", "duc_tri", "Xin chào."))
        assertTrue(a != TtsAudioPolicy.cacheKey("https://h2", "my_duyen", "Xin chào."))
        assertTrue(a != TtsAudioPolicy.cacheKey("https://h", "my_duyen", "Xin chào!"))
        assertTrue(TtsStreamUrl.build("https://h", "Xin chào.", null, "my_duyen").contains("&speed=1.0&"), "URL cố định 1.0x")
    }

    @Test
    fun choDuLauChoLanDungTaiChoVaKhongTaiHaiLan() = runBlocking {
        var calls = 0
        var timeoutSeen = 0L
        val files = MemoryFiles()
        val transport = TtsAudioTransport { _, timeoutMs ->
            calls++
            timeoutSeen = timeoutMs
            delay(50) // máy chủ đang dựng tại chỗ
            response(200, mapOf("x-audio-duration-ms" to "1000"), ByteArray(384))
        }
        val fetcher = TtsAudioFetcher(transport, files, CoroutineScope(Dispatchers.Default), ioDispatcher = Dispatchers.Default)
        val results = (1..3).map { async { fetcher.fetch("u", "k") } }.awaitAll()
        assertEquals(1, calls, "tải sẵn + lúc tới lượt phát dùng chung một lượt tải")
        assertTrue(results.all { it == TtsAudioFetch.Ready("/cache/k.mp3", 1000L) }, "$results")
        assertTrue(timeoutSeen >= 90_000L + 10_000L, "chờ được hết TTS_ONDEMAND_TIMEOUT (90 s) + 10 s")
        assertEquals(TtsAudioFetch.Ready("/cache/k.mp3", 48L), fetcher.fetch("u", "k"), "lần sau lấy tệp trên máy, không gọi mạng")
        assertEquals(1, calls)
    }

    @Test
    fun matMangLaLoiMayChuConTiengTrungThiKhong() = runBlocking {
        val failing = TtsAudioFetcher(
            { _, _ -> throw IllegalStateException("timeout") }, MemoryFiles(), CoroutineScope(Dispatchers.Default),
            ioDispatcher = Dispatchers.Default
        )
        assertTrue(failing.fetch("u", "k").isServerFailure)
        val zh = TtsAudioFetcher(
            { _, _ -> response(422, body = """{"detail":"unsupported_language"}""".encodeToByteArray()) }, MemoryFiles(),
            CoroutineScope(Dispatchers.Default), ioDispatcher = Dispatchers.Default
        )
        assertFalse(zh.fetch("u", "k").isServerFailure)
    }
}
