package com.truyencloud.shared.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine

/**
 * Chạy một hàm suspend KHÔNG thật sự tạm dừng (chỉ gọi các DAO giả trả ngay) mà không cần thư viện
 * coroutines-test. Ném lỗi nếu khối lệnh thật sự bị treo — khi đó hãy dùng một cách kiểm thử khác.
 */
fun <T> runNow(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return (outcome ?: error("Khối lệnh bị treo: DAO giả không được phép tạm dừng")).getOrThrow()
}

/** NovelDao giả trong bộ nhớ: chỉ hiện thực phần các bài kiểm thử cần. */
class FakeNovelDao : NovelDao {
    val rows = LinkedHashMap<String, Novel>()

    override fun getAllNovels(): Flow<List<Novel>> = flowOf(rows.values.toList())
    override fun getNovelsByShelf(shelf: String): Flow<List<Novel>> = flowOf(rows.values.filter { it.shelfName == shelf })
    override suspend fun getNovelById(id: String): Novel? = rows[id]
    override fun observeNovel(id: String): Flow<Novel?> = flowOf(rows[id])
    override fun getFavoriteNovels(): Flow<List<Novel>> = flowOf(rows.values.filter { it.isFavorite })
    override suspend fun upsertNovel(novel: Novel) { rows[novel.id] = novel }
    override suspend fun insertIfAbsent(novel: Novel): Long =
        if (rows.containsKey(novel.id)) -1L else { rows[novel.id] = novel; 1L }

    override suspend fun updateMetadata(
        id: String, title: String, titleVi: String, author: String, url: String,
        coverUrl: String, sourceId: String, totalChapters: Int, description: String, status: String
    ): Int {
        val n = rows[id] ?: return 0
        rows[id] = n.copy(
            title = title, titleVi = titleVi, author = author, url = url, coverUrl = coverUrl,
            sourceId = sourceId, totalChapters = totalChapters, description = description, status = status
        )
        return 1
    }

    override suspend fun updateShelf(id: String, shelf: String, stamp: Long): Int {
        val n = rows[id] ?: return 0
        rows[id] = n.copy(shelfName = shelf, syncUpdatedAt = stamp, syncDirty = true)
        return 1
    }

    override suspend fun setFavorite(id: String, favorite: Boolean, stamp: Long): Int {
        val n = rows[id] ?: return 0
        rows[id] = n.copy(isFavorite = favorite, syncUpdatedAt = stamp, syncDirty = true)
        return 1
    }

    override suspend fun deleteNovelById(id: String) { rows.remove(id) }
    override suspend fun deleteAll() { rows.clear() }

    override suspend fun updateReadingProgress(id: String, chapter: Int, timestamp: Long): Int {
        val n = rows[id] ?: return 0
        rows[id] = n.copy(lastReadChapter = chapter, lastReadAt = timestamp, syncUpdatedAt = timestamp, syncDirty = true)
        return 1
    }

    override suspend fun countNovels(): Int = rows.size
    override suspend fun getDirtyNovels(): List<Novel> = rows.values.filter { it.syncDirty }

    override suspend fun stampIfUnset(id: String, stamp: Long): Int {
        val n = rows[id] ?: return 0
        if (n.syncUpdatedAt != 0L || n.lastReadAt != 0L) return 0
        rows[id] = n.copy(syncUpdatedAt = stamp)
        return 1
    }

    override suspend fun clearDirtyIfUnchanged(id: String, stamp: Long): Int {
        val n = rows[id] ?: return 0
        if (n.syncUpdatedAt != stamp) return 0
        rows[id] = n.copy(syncDirty = false)
        return 1
    }

    override suspend fun applyRemoteProgress(id: String, chapter: Int, stamp: Long, shelf: String, favorite: Boolean): Int {
        val n = rows[id] ?: return 0
        rows[id] = n.copy(
            lastReadChapter = chapter, lastReadAt = stamp, shelfName = shelf, isFavorite = favorite,
            syncUpdatedAt = stamp, syncDirty = false
        )
        return 1
    }
}

/** ChapterDao giả: đủ cho vị trí cuộn và chèn chương tối thiểu. */
class FakeChapterDao : ChapterDao {
    val rows = LinkedHashMap<String, Chapter>()

    private fun find(novelId: String, n: Int) = rows.values.firstOrNull { it.novelId == novelId && it.chapterNumber == n }

    override fun getChaptersForNovel(novelId: String): Flow<List<Chapter>> = flowOf(rows.values.filter { it.novelId == novelId })
    override suspend fun getChaptersForNovelOnce(novelId: String): List<Chapter> = rows.values.filter { it.novelId == novelId }
    override fun getChaptersForNovelLight(novelId: String): Flow<List<Chapter>> = flowOf(lightList(novelId))
    override suspend fun getChapterListLight(novelId: String): List<Chapter> = lightList(novelId)

    private fun lightList(novelId: String) = rows.values.filter { it.novelId == novelId }.sortedBy { it.chapterNumber }
        .map { it.copy(originalContent = null, translatedContent = null, translatedContentEn = "", translatedContentBilingual = "") }

    override suspend fun getChapter(novelId: String, chapterNumber: Int): Chapter? = find(novelId, chapterNumber)
    override suspend fun getMaxChapterNumber(novelId: String): Int? = rows.values.filter { it.novelId == novelId }.maxOfOrNull { it.chapterNumber }
    override suspend fun upsertChapter(chapter: Chapter) { rows[chapter.id] = chapter }
    override suspend fun upsertChapters(chapters: List<Chapter>) { chapters.forEach { rows[it.id] = it } }

    override suspend fun insertChaptersIfAbsent(chapters: List<Chapter>): List<Long> =
        chapters.map { c ->
            if (find(c.novelId, c.chapterNumber) != null) -1L else { rows[c.id] = c; 1L }
        }

    override suspend fun updateChapterMetadata(novelId: String, chapterNumber: Int, title: String, titleVi: String, url: String): Int {
        val c = find(novelId, chapterNumber) ?: return 0
        rows[c.id] = c.copy(title = title, titleVi = titleVi, url = url)
        return 1
    }

    override suspend fun updateFetchedContent(
        novelId: String, chapterNumber: Int, title: String, titleVi: String,
        original: String, translated: String?, engine: String?
    ): Int = TODO("không dùng trong kiểm thử")

    override suspend fun updateTranslation(novelId: String, chapterNumber: Int, translated: String, engine: String, titleVi: String) {
        find(novelId, chapterNumber)?.let {
            rows[it.id] = it.copy(
                translatedContent = translated,
                translationEngine = engine,
                titleVi = titleVi.ifBlank { it.titleVi }
            )
        }
    }

    override suspend fun updateEnglishTranslation(novelId: String, chapterNumber: Int, content: String, level: String) = TODO()
    override suspend fun updateBilingualTranslation(novelId: String, chapterNumber: Int, content: String, level: String) = TODO()

    override suspend fun updateReadingProgress(novelId: String, chapterNumber: Int, scroll: Float, markRead: Boolean): Int {
        val c = find(novelId, chapterNumber) ?: return 0
        rows[c.id] = c.copy(scrollPosition = scroll, isRead = c.isRead || markRead)
        return 1
    }

    override suspend fun markRead(novelId: String, chapterNumber: Int): Int {
        val c = find(novelId, chapterNumber) ?: return 0
        rows[c.id] = c.copy(isRead = true)
        return 1
    }

    override fun observeChapterFlags(novelId: String): Flow<List<ChapterFlags>> = TODO()
    override suspend fun getChapterFlags(novelId: String): List<ChapterFlags> = TODO()
    override suspend fun getChapterFlag(novelId: String, chapterNumber: Int): ChapterFlags? = TODO()
    override fun getDownloadedChapterNumbers(novelId: String): Flow<List<Int>> = TODO()
    override suspend fun getDownloadedChapterCount(novelId: String): Int = TODO()
    override suspend fun deleteChaptersForNovel(novelId: String) { rows.values.removeAll { it.novelId == novelId } }
    override suspend fun getScrollPosition(novelId: String, chapterNumber: Int): Float? = find(novelId, chapterNumber)?.scrollPosition
    override suspend fun clearAllContent(): Int = TODO()
    override suspend fun estimateContentBytes(): Long? = TODO()
    override suspend fun estimateContentBytesForNovel(novelId: String): Long = TODO()
    override suspend fun clearContentForNovel(novelId: String): Int = TODO()
    override suspend fun getCachedNovelSummaries(): List<CachedNovelSummary> = TODO()
}

class FakeSyncStateDao : SyncStateDao {
    val rows = LinkedHashMap<String, SyncState>()
    override suspend fun getLong(key: String): Long? = rows[key]?.longValue
    override suspend fun listByPrefix(prefix: String): List<SyncState> = rows.values.filter { it.stateKey.startsWith(prefix) }
    override suspend fun put(state: SyncState) { rows[state.stateKey] = state }
    override suspend fun delete(key: String) { rows.remove(key) }
}
