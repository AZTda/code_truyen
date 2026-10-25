package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ApiHttpException
import com.truyencloud.shared.api.BookmarkRemote
import com.truyencloud.shared.api.BookmarkRemoteItem
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.data.BookmarkDao
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.SyncState
import com.truyencloud.shared.data.SyncStateDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Đồng bộ hai chiều dấu trang / highlight / ghi chú với máy chủ.
 *
 * Giao thức (khớp `server/routes/bookmarks.py`):
 * - ĐẨY các dòng `syncDirty` (kể cả bia mộ xoá), tối đa 500 dòng mỗi yêu cầu.
 * - KÉO `GET /bookmarks?since=<con trỏ>` — LUÔN gửi `since` (0 ở lần đầu) vì máy chủ chỉ trả bia
 *   mộ khi có tham số này; con trỏ là header `X-Server-Time-Ms`, lưu riêng cho từng tài khoản.
 * - Hợp nhất theo giờ sửa (xem [BookmarkMerge]); bia mộ thắng khi bằng giờ.
 * - Giãn cách: tối đa một lượt mỗi [MIN_INTERVAL_MS], trừ khi ép chạy hoặc vừa đổi tài khoản.
 *
 * Bản cũ đẩy TOÀN BỘ bookmark rồi kéo TOÀN BỘ về ở mỗi lần mở chương, mỗi lần lưu và cả onPause
 * lẫn onStop.
 */
internal class BookmarkSyncCoordinator(
    private val bookmarkDao: BookmarkDao,
    private val bookmarkApi: BookmarkRemote,
    private val syncStateDao: SyncStateDao?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { getCurrentEpochMillis() }
) {
    private val throttle = SyncThrottle(MIN_INTERVAL_MS, clock)
    private val running = Mutex()
    private var trailing: Job? = null
    private var dirtyDuringSync = false

    /**
     * Chạy một lượt đồng bộ (đã giãn cách). Khách chưa đăng nhập không đồng bộ.
     * @return số bookmark hợp nhất từ máy chủ; 0 cũng là kết quả khi lượt này bị bỏ qua vì giãn cách.
     */
    suspend fun sync(force: Boolean = false): Result<Int> {
        val token = TruyenCloudApi.authToken
        if (token.isBlank()) return Result.success(0)
        val account = SyncAccount.keyFor(token)
        if (!throttle.shouldRun(force, account)) return Result.success(0)
        // Lượt ép buộc (force) chờ khóa để chắc chắn chạy; lượt tự động bỏ qua nếu đang có lượt khác chạy.
        if (force) {
            running.lock()
        } else if (!running.tryLock()) {
            return Result.success(0)
        }
        return try {
            throttle.markStarted(account)
            val merged = runSync(account)
            SyncStatus.reportSuccess()
            Result.success(merged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (ApiErrors.isAuthError(e)) {
                val refreshedToken = TruyenCloudApi.authToken
                if (refreshedToken.isNotBlank() && refreshedToken != token) {
                    return try {
                        val merged = runSync(SyncAccount.keyFor(refreshedToken))
                        SyncStatus.reportSuccess()
                        Result.success(merged)
                    } catch (retryEx: Exception) {
                        if (ApiErrors.isAuthError(retryEx)) SyncStatus.reportSessionExpired()
                        else SyncStatus.reportFailure(ApiErrors.describe(retryEx))
                        Result.failure(retryEx)
                    }
                }
                SyncStatus.reportSessionExpired()
            } else SyncStatus.reportFailure(ApiErrors.describe(e))
            Result.failure(e)
        } finally {
            running.unlock()
        }
    }

    /**
     * Hẹn một lượt đồng bộ sau khi người dùng sửa/xoá dấu trang: chạy ngay nếu đã qua khoảng
     * giãn cách, không thì chờ đủ rồi chạy một lần (gộp mọi lần sửa trong khoảng chờ).
     */
    fun requestSync() {
        val token = TruyenCloudApi.authToken
        if (token.isBlank()) return
        dirtyDuringSync = true
        if (trailing?.isActive == true) return
        val account = SyncAccount.keyFor(token)
        trailing = scope.launch {
            while (dirtyDuringSync) {
                dirtyDuringSync = false
                while (true) {
                    val wait = throttle.millisUntilNext(account)
                    if (wait <= 0L) break
                    delay(wait)
                }
                sync(force = false)
            }
        }
    }

    private suspend fun runSync(account: String): Int {
        // Hàng có từ trước chưa có giờ sửa: lấy giờ tạo để so sánh được với máy chủ.
        bookmarkDao.backfillUpdatedAt()

        dirtyDuringSync = false
        val pushStart = clock()
        val serverHadNewer = pushDirty(pushStart)

        val cursorKey = SyncKeys.bookmarkCursor(account)
        val storedCursor = syncStateDao?.getLong(cursorKey) ?: 0L
        // Máy chủ báo giữ bản mới hơn (stale): bản đó có thể đã nằm DƯỚI con trỏ (vd. đồng hồ máy này
        // chạy chậm hơn máy kia) nên kéo đầy đủ từ 0 để chắc chắn lấy được.
        val since = if (serverHadNewer) 0L else storedCursor
        val page = bookmarkApi.pullBookmarks(since)
        val merged = merge(page.items)

        syncStateDao?.put(
            SyncState(cursorKey, longValue = BookmarkMerge.nextCursor(page.serverTimeMs, storedCursor))
        )
        bookmarkDao.purgeTombstonesBefore(BookmarkMerge.tombstoneCutoff(clock()))
        return merged
    }

    /** @return true nếu máy chủ báo có dòng nó đang giữ bản mới hơn. */
    private suspend fun pushDirty(pushStart: Long): Boolean {
        val dirty = bookmarkDao.getDirtyBookmarks()
        if (dirty.isEmpty()) return false
        // Dòng thiếu id/novelId sẽ khiến cả yêu cầu bị máy chủ từ chối (422) — không đẩy, không chặn các dòng khác.
        val (pushable, unsendable) = dirty.partition { it.id.isNotBlank() && it.novelId.isNotBlank() }
        if (unsendable.isNotEmpty()) bookmarkDao.markPushed(unsendable.map { it.id }, pushStart)

        var serverHadNewer = false
        for (chunk in pushable.chunked(BookmarkMerge.MAX_PUSH_BATCH)) {
            if (pushChunk(chunk, pushStart)) serverHadNewer = true
        }
        return serverHadNewer
    }

    private suspend fun pushChunk(chunk: List<Bookmark>, pushStart: Long): Boolean {
        return try {
            val resp = bookmarkApi.syncBookmarks(chunk.map { it.toRemote() })
            bookmarkDao.markPushed(chunk.map { it.id }, pushStart)
            resp.stale_ids.isNotEmpty()
        } catch (e: ApiHttpException) {
            // 4xx (trừ hết phiên / quá tải) nghĩa là có dòng hỏng khiến cả lô bị từ chối: tách lẻ để
            // cô lập dòng hỏng thay vì để nó chặn đồng bộ vĩnh viễn.
            if (chunk.size > 1 && isPermanentClientError(e)) {
                var stale = false
                for (single in chunk) if (pushChunk(listOf(single), pushStart)) stale = true
                stale
            } else if (chunk.size == 1 && isPermanentClientError(e)) {
                bookmarkDao.markPushed(chunk.map { it.id }, pushStart) // dòng không bao giờ được chấp nhận: bỏ đẩy
                false
            } else throw e
        }
    }

    private fun isPermanentClientError(e: ApiHttpException): Boolean =
        e.statusCode in 400..499 && e.statusCode != 401 && e.statusCode != 408 && e.statusCode != 429

    /** @return số bookmark đã thay đổi cục bộ theo máy chủ. */
    private suspend fun merge(items: List<BookmarkRemoteItem>): Int {
        var applied = 0
        for (chunk in items.chunked(BookmarkMerge.MAX_PUSH_BATCH)) {
            val locals = bookmarkDao.getByIds(chunk.map { it.id }).associateBy { it.id }
            val toUpsert = ArrayList<Bookmark>()
            val toDelete = ArrayList<String>()
            for (remote in chunk) {
                val local = locals[remote.id]
                val action = BookmarkMerge.decide(
                    localStamp = local?.effectiveUpdatedAt,
                    localDeleted = local?.isDeleted == true,
                    remoteStamp = remote.stamp,
                    remoteDeleted = remote.isTombstone
                )
                when (action) {
                    BookmarkMergeAction.TAKE_REMOTE -> if (remote.isValidLive()) {
                        toUpsert.add(remote.toLocal())
                        applied++
                    }
                    BookmarkMergeAction.DELETE_LOCAL -> if (local != null) {
                        toDelete.add(local.id)
                        applied++
                    }
                    BookmarkMergeAction.KEEP_LOCAL, BookmarkMergeAction.IGNORE -> Unit
                }
            }
            if (toUpsert.isNotEmpty()) bookmarkDao.upsertAll(toUpsert)
            if (toDelete.isNotEmpty()) bookmarkDao.hardDeleteByIds(toDelete)
        }
        return applied
    }

    // Giới hạn trường của máy chủ: vượt là bị từ chối cả lô.
    private fun Bookmark.toRemote() = BookmarkRemoteItem(
        id = id.take(MAX_ID),
        novel_id = novelId.take(MAX_ID),
        chapter_number = chapterNumber.coerceAtLeast(1),
        chapter_title = chapterTitle.take(MAX_TITLE),
        paragraph_index = paragraphIndex.coerceAtLeast(0),
        selected_text = selectedText.take(MAX_TEXT),
        note = note.take(MAX_NOTE),
        color_hex = colorHex.take(MAX_COLOR),
        created_at = createdAt.coerceAtLeast(0L),
        updated_at = effectiveUpdatedAt,
        deleted_at = deletedAt
    )

    private fun BookmarkRemoteItem.toLocal() = Bookmark(
        id = id,
        novelId = novel_id,
        chapterNumber = chapter_number,
        chapterTitle = chapter_title,
        paragraphIndex = paragraph_index,
        selectedText = selected_text,
        note = note,
        colorHex = color_hex,
        createdAt = created_at,
        updatedAt = if (updated_at > 0L) updated_at else created_at,
        deletedAt = 0L,
        syncDirty = false
    )

    /** Bản sống hợp lệ để lưu (bia mộ giữ chỗ của máy chủ dùng novel_id = "_deleted"). */
    private fun BookmarkRemoteItem.isValidLive() =
        !isTombstone && id.isNotBlank() && novel_id.isNotBlank() && novel_id != "_deleted" && chapter_number >= 1

    companion object {
        /** Giãn cách tối thiểu giữa hai lượt đồng bộ không ép buộc. */
        const val MIN_INTERVAL_MS = 30_000L

        private const val MAX_ID = 200
        private const val MAX_TITLE = 500
        private const val MAX_TEXT = 20_000
        private const val MAX_NOTE = 8_000
        private const val MAX_COLOR = 20
    }
}
