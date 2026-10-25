package com.truyencloud.shared.tts

import android.content.Context
import java.io.File

/** Tệp âm thanh giọng AI đã tải, trong thư mục cache của ứng dụng (hệ thống được phép tự dọn khi thiếu chỗ). */
internal class AndroidTtsAudioFileStore(context: Context) : TtsAudioFileStore {
    private val dir: File = File(context.applicationContext.cacheDir, "tts_audio")

    private fun file(key: String) = File(dir, "$key.mp3")

    override fun pathFor(key: String): String = file(key).absolutePath

    override fun sizeOf(key: String): Long {
        val f = file(key)
        if (!f.isFile) return 0L
        // Đánh dấu vừa dùng để lần dọn bộ đệm xoá tệp lâu không nghe trước
        runCatching { f.setLastModified(System.currentTimeMillis()) }
        return f.length()
    }

    override fun write(key: String, bytes: ByteArray): Boolean = try {
        if (!dir.isDirectory) dir.mkdirs()
        val tmp = File(dir, "$key.part")
        tmp.writeBytes(bytes)
        tmp.renameTo(file(key)) || run { tmp.delete(); false }
    } catch (_: Exception) {
        false
    }

    override fun trim(maxBytes: Long) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp3") } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        if (total <= maxBytes && count <= MAX_CACHED_SEGMENTS) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes && count <= MAX_CACHED_SEGMENTS) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                count--
            }
        }
    }

    companion object {
        const val MAX_CACHED_SEGMENTS = 200
    }
}
