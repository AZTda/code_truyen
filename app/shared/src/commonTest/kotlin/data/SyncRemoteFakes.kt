package com.truyencloud.shared.data

import com.truyencloud.shared.api.ApiHttpException
import com.truyencloud.shared.api.BookmarkPullResult
import com.truyencloud.shared.api.BookmarkRemote
import com.truyencloud.shared.api.BookmarkRemoteItem
import com.truyencloud.shared.api.ProgressRemote
import com.truyencloud.shared.api.ProgressRemoteResponse
import com.truyencloud.shared.api.SyncBookmarksRemoteResponse
import com.truyencloud.shared.api.SyncProgressResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** BookmarkDao giả trong bộ nhớ. */
class FakeBookmarkDao : BookmarkDao {
    val rows = LinkedHashMap<String, Bookmark>()

    override suspend fun upsertBookmark(bookmark: Bookmark) { rows[bookmark.id] = bookmark }
    override suspend fun upsertAll(bookmarks: List<Bookmark>) { bookmarks.forEach { rows[it.id] = it } }
    override suspend fun getById(id: String): Bookmark? = rows[id]
    override suspend fun getByIds(ids: List<String>): List<Bookmark> = ids.mapNotNull { rows[it] }

    private fun alive() = rows.values.filter { it.deletedAt == 0L }
    override fun getBookmarksForChapter(novelId: String, chapterNumber: Int): Flow<List<Bookmark>> =
        flowOf(alive().filter { it.novelId == novelId && it.chapterNumber == chapterNumber })

    override fun getBookmarksForNovel(novelId: String): Flow<List<Bookmark>> =
        flowOf(alive().filter { it.novelId == novelId }.sortedWith(compareBy({ it.chapterNumber }, { it.paragraphIndex }, { it.createdAt })))

    override fun getAllBookmarks(): Flow<List<Bookmark>> = flowOf(alive())
    override suspend fun getAllBookmarksList(): List<Bookmark> = alive()

    override suspend fun softDelete(id: String, now: Long): Int {
        val b = rows[id] ?: return 0
        if (b.deletedAt != 0L) return 0
        rows[id] = b.copy(deletedAt = now, updatedAt = now, syncDirty = true)
        return 1
    }

    override suspend fun softDeleteForNovel(novelId: String, now: Long): Int {
        var n = 0
        rows.values.filter { it.novelId == novelId && it.deletedAt == 0L }.forEach { n += softDelete(it.id, now) }
        return n
    }

    override suspend fun getDirtyBookmarks(): List<Bookmark> = rows.values.filter { it.syncDirty }

    override suspend fun markPushed(ids: List<String>, beforeMs: Long): Int {
        var n = 0
        ids.forEach { id ->
            val b = rows[id] ?: return@forEach
            if (b.updatedAt <= beforeMs) { rows[id] = b.copy(syncDirty = false); n++ }
        }
        return n
    }

    override suspend fun hardDeleteByIds(ids: List<String>): Int = ids.count { rows.remove(it) != null }

    override suspend fun purgeTombstonesBefore(cutoffMs: Long): Int {
        val doomed = rows.values.filter { it.deletedAt > 0 && it.deletedAt < cutoffMs && !it.syncDirty }.map { it.id }
        doomed.forEach { rows.remove(it) }
        return doomed.size
    }

    override suspend fun migrateNovelId(oldId: String, newId: String, now: Long): Int {
        var n = 0
        rows.values.filter { it.novelId == oldId }.forEach { rows[it.id] = it.copy(novelId = newId, updatedAt = now, syncDirty = true); n++ }
        return n
    }

    override suspend fun backfillUpdatedAt(): Int {
        var n = 0
        rows.values.filter { it.updatedAt == 0L }.forEach { rows[it.id] = it.copy(updatedAt = it.createdAt); n++ }
        return n
    }
}

/**
 * Máy chủ dấu trang giả, mô phỏng đúng luật của `server/routes/bookmarks.py`: tối đa 500 dòng mỗi lượt,
 * last-write-wins theo max(updated_at, deleted_at) và bia mộ thắng khi bằng giờ, `stale_ids`, và chỉ trả
 * bia mộ khi nhận được `since`.
 */
class FakeBookmarkRemote(private val clock: () -> Long) : BookmarkRemote {
    val server = LinkedHashMap<String, BookmarkRemoteItem>()
    private val changedAt = HashMap<String, Long>()
    val pushBatchSizes = ArrayList<Int>()
    val pullSinceValues = ArrayList<Long>()
    var failWith: Throwable? = null

    /** Dòng có note này khiến máy chủ từ chối cả lô bằng 422 (mô phỏng dữ liệu hỏng). */
    var poisonNote: String? = null

    private fun incomingWins(inTs: Long, inDeleted: Boolean, stTs: Long, stDeleted: Boolean): Boolean {
        if (inTs != stTs) return inTs > stTs
        return inDeleted || !stDeleted
    }

    override suspend fun syncBookmarks(bookmarks: List<BookmarkRemoteItem>): SyncBookmarksRemoteResponse {
        failWith?.let { throw it }
        pushBatchSizes += bookmarks.size
        if (bookmarks.size > 500) throw ApiHttpException(422, "tối đa 500")
        if (bookmarks.any { it.id.isBlank() || it.novel_id.isBlank() || it.chapter_number < 1 }) throw ApiHttpException(422, "dữ liệu sai")
        if (poisonNote != null && bookmarks.any { it.note == poisonNote }) throw ApiHttpException(422, "dòng hỏng")
        val stale = ArrayList<String>()
        var applied = 0
        for (bm in bookmarks) {
            val stored = server[bm.id]
            val ts = bm.stamp
            if (stored != null && !incomingWins(ts, bm.isTombstone, stored.stamp, stored.isTombstone)) {
                stale += bm.id
                continue
            }
            server[bm.id] = bm
            changedAt[bm.id] = clock()
            applied++
        }
        return SyncBookmarksRemoteResponse(count = bookmarks.size, applied = applied, stale_ids = stale, server_time_ms = clock())
    }

    override suspend fun pullBookmarks(since: Long): BookmarkPullResult {
        failWith?.let { throw it }
        pullSinceValues += since
        val items = server.values.filter { it.stamp > since || (changedAt[it.id] ?: 0L) > since }
        return BookmarkPullResult(items, clock())
    }
}

/** Máy chủ tiến độ giả: last-write-wins theo client_updated_at, trả "stale" khi bản máy chủ mới hơn. */
class FakeProgressRemote : ProgressRemote {
    val server = LinkedHashMap<String, ProgressRemoteResponse>()
    val pushed = ArrayList<PushedProgress>()
    var failWith: Throwable? = null
    var getProgressCalls = 0

    /** Chỉ POST /progress/sync hỏng (GET vẫn chạy): nửa ĐẨY hụt mà nửa KÉO thành công. */
    var failPushes: Throwable? = null

    /** Chỉ lời đẩy bia mộ (shelf = "removed") hỏng — mô phỏng bia mộ "độc" làm 503 / 409 mãi. */
    var failRemovals: Throwable? = null

    /** Lời đẩy cho các truyện này hỏng. */
    val failNovels = HashMap<String, Throwable>()

    data class PushedProgress(
        val novelId: String, val chapter: Int, val scroll: Float, val shelf: String, val favorite: Boolean, val stamp: Long?
    )

    override suspend fun syncProgress(
        novelId: String, currentChapter: Int, scrollPosition: Float, shelfName: String,
        isFavorite: Boolean, clientUpdatedAt: Long?
    ): SyncProgressResponse {
        failWith?.let { throw it }
        failPushes?.let { throw it }
        if (shelfName == "removed") failRemovals?.let { throw it }
        failNovels[novelId]?.let { throw it }
        pushed += PushedProgress(novelId, currentChapter, scrollPosition, shelfName, isFavorite, clientUpdatedAt)
        val stamp = clientUpdatedAt ?: 1L
        val stored = server[novelId]
        if (stored != null && stored.updated_at_ms > stamp) {
            return SyncProgressResponse(status = "stale", updated_at_ms = stored.updated_at_ms, progress = stored)
        }
        server[novelId] = ProgressRemoteResponse(
            novel_id = novelId, current_chapter = currentChapter, scroll_position = scrollPosition,
            shelf_name = shelfName, is_favorite = isFavorite, updated_at_ms = stamp
        )
        return SyncProgressResponse(status = "success", updated_at_ms = stamp)
    }

    override suspend fun getProgress(novelId: String): ProgressRemoteResponse {
        failWith?.let { throw it }
        getProgressCalls++
        return server[novelId] ?: ProgressRemoteResponse(novel_id = novelId)
    }

    override suspend fun getAllProgress(): List<ProgressRemoteResponse> {
        failWith?.let { throw it }
        return server.values.toList()
    }
}
