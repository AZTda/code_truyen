@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.truyencloud.shared.tts

import com.truyencloud.shared.api.TruyenCloudApi
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/** Tệp âm thanh giọng AI đã tải, trong thư mục Caches của ứng dụng (iOS được phép tự dọn khi thiếu chỗ). */
internal class IosTtsAudioFileStore : TtsAudioFileStore {
    private val fm = NSFileManager.defaultManager
    private val dir: String by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
        "${caches ?: "/tmp"}/tts_audio"
    }

    private fun file(key: String) = "$dir/$key.mp3"

    override fun pathFor(key: String): String = file(key)

    override fun sizeOf(key: String): Long {
        val path = file(key)
        val size = sizeAt(path)
        // Đánh dấu vừa dùng để lần dọn bộ đệm xoá tệp lâu không nghe trước
        if (size > 0L) runCatching { fm.setAttributes(mapOf(NSFileModificationDate to NSDate()), ofItemAtPath = path, error = null) }
        return size
    }

    override fun write(key: String, bytes: ByteArray): Boolean = try {
        if (bytes.isEmpty()) {
            false
        } else {
            if (!fm.fileExistsAtPath(dir)) fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
            val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
            data.writeToFile(file(key), atomically = true)
        }
    } catch (_: Exception) {
        false
    }

    override fun trim(maxBytes: Long) {
        val names = fm.contentsOfDirectoryAtPath(dir, null)?.filterIsInstance<String>()?.filter { it.endsWith(".mp3") } ?: return
        val files = names.map { name ->
            val path = "$dir/$name"
            val attrs = fm.attributesOfItemAtPath(path, null)
            Triple(path, sizeAt(path), (attrs?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970 ?: 0.0)
        }
        var total = files.sumOf { it.second }
        var count = files.size
        if (total <= maxBytes && count <= MAX_CACHED_SEGMENTS) return
        for ((path, size, _) in files.sortedBy { it.third }) {
            if (total <= maxBytes && count <= MAX_CACHED_SEGMENTS) break
            if (fm.removeItemAtPath(path, null)) {
                total -= size
                count--
            }
        }
    }

    private fun sizeAt(path: String): Long = try {
        (fm.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
    } catch (_: Exception) {
        0L
    }

    companion object {
        const val MAX_CACHED_SEGMENTS = 200
        private const val PREFETCH_AHEAD_COUNT = 3

        /** Gửi trước tối đa 3 mảnh kế tiếp lên `/v1/tts/prefetch` để MiniPC tổng hợp sẵn cho iOS. */
        fun prefetchUpcoming(
            scope: CoroutineScope,
            serverUrl: String,
            texts: List<String>,
            currentIdx: Int,
            profileId: String
        ) {
            val indices = TtsSegmentPlan.serverAhead(texts, currentIdx, count = PREFETCH_AHEAD_COUNT)
            if (indices.isEmpty()) return
            val slice = indices.map { texts[it].trim() }.filter { it.isNotEmpty() }
            if (slice.isEmpty()) return
            val endpoint = "${serverUrl.trimEnd('/')}/v1/tts/prefetch"
            slice.groupBy(TtsTextLanguage::langParam).forEach { (lang, group) ->
                val body = JsonObject(
                    mapOf(
                        "texts" to JsonArray(group.map { JsonPrimitive(it) }),
                        "voice" to JsonPrimitive(profileId),
                        "speed" to JsonPrimitive(1.0),
                        "lang" to JsonPrimitive(lang)
                    )
                ).toString()
                scope.launch {
                    runCatching {
                        TruyenCloudApi.httpClient.post(endpoint) {
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    }
                }
            }
        }
    }
}
