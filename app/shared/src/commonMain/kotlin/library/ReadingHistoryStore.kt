package com.truyencloud.shared.library

import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayAuthor
import com.truyencloud.shared.data.displayTitle
import com.truyencloud.shared.data.getCurrentEpochMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class ReadingHistoryItem(
    val novelId: String,
    val novelTitle: String,
    val author: String = "",
    val coverUrl: String = "",
    val chapterNumber: Int,
    val chapterTitle: String = "",
    val timestampMs: Long,
    val isAudio: Boolean = false
)

/**
 * Nhật ký lịch sử đọc / nghe chi tiết theo từng chương, lưu trong [HomePrefsStore] (không đổi lược đồ Room).
 * Tự động gộp với `recentNovels` trong tủ sách để hiển thị đầy đủ kể cả dữ liệu đọc từ trước.
 */
class ReadingHistoryStore(
    private val prefs: HomePrefsStore? = defaultHomePrefs(),
    private val maxEntries: Int = MAX_ENTRIES
) {
    fun all(): List<ReadingHistoryItem> {
        val raw = runCatching { prefs?.getString(KEY) }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun record(
        novel: Novel,
        chapterNumber: Int,
        chapterTitle: String = "",
        isAudio: Boolean = false,
        nowMs: Long = getCurrentEpochMillis()
    ) {
        if (novel.id.isBlank() || chapterNumber <= 0) return
        val item = ReadingHistoryItem(
            novelId = novel.id,
            novelTitle = novel.displayTitle,
            author = novel.displayAuthor,
            coverUrl = novel.coverUrl,
            chapterNumber = chapterNumber,
            chapterTitle = chapterTitle.trim().ifBlank { "Chương $chapterNumber" },
            timestampMs = nowMs,
            isAudio = isAudio
        )
        val filtered = all().filterNot { it.novelId == novel.id && it.chapterNumber == chapterNumber }
        write((listOf(item) + filtered).take(maxEntries))
    }

    fun mergedWith(libraryNovels: List<Novel>): List<ReadingHistoryItem> {
        val recorded = all()
        val seenNovels = recorded.mapTo(HashSet()) { it.novelId }
        val fallback = libraryNovels
            .filter { it.lastReadChapter > 0 && it.id !in seenNovels }
            .map { n ->
                ReadingHistoryItem(
                    novelId = n.id,
                    novelTitle = n.displayTitle,
                    author = n.displayAuthor,
                    coverUrl = n.coverUrl,
                    chapterNumber = n.lastReadChapter,
                    chapterTitle = "Chương ${n.lastReadChapter}",
                    timestampMs = n.lastReadAt,
                    isAudio = false
                )
            }
        return (recorded + fallback).sortedByDescending { it.timestampMs }.take(maxEntries)
    }

    fun clear() {
        runCatching { prefs?.remove(KEY) }
    }

    private fun write(items: List<ReadingHistoryItem>) {
        runCatching {
            if (items.isEmpty()) prefs?.remove(KEY)
            else prefs?.putString(KEY, json.encodeToString(serializer, items))
        }
    }

    companion object {
        const val KEY = "reading_history_timeline_v1"
        const val MAX_ENTRIES = 60
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(ReadingHistoryItem.serializer())

        val shared: ReadingHistoryStore by lazy { ReadingHistoryStore(defaultHomePrefs()) }

        fun relativeTimeLabel(timestampMs: Long, nowMs: Long = getCurrentEpochMillis()): String {
            if (timestampMs <= 0L) return "Gần đây"
            val diffSec = ((nowMs - timestampMs) / 1000L).coerceAtLeast(0L)
            return when {
                diffSec < 60L -> "Vừa xong"
                diffSec < 3600L -> "${diffSec / 60L} phút trước"
                diffSec < 86_400L -> "${diffSec / 3600L} giờ trước"
                diffSec < 172_800L -> "Hôm qua"
                diffSec < 30L * 86_400L -> "${diffSec / 86_400L} ngày trước"
                else -> "${diffSec / (30L * 86_400L)} tháng trước"
            }
        }
    }
}
