package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ProgressRemote
import com.truyencloud.shared.api.ProgressRemoteResponse
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.ChapterDao
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.NovelDao
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.SyncStateDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Kết quả một lượt đẩy: [failed] = có ít nhất một hàng / bia mộ đẩy hụt (cờ dirty của chúng vẫn còn). */
internal class FlushResult(val pushed: Int, val failed: Boolean)

/**
 * Kết quả đồng bộ toàn bộ (đẩy rồi kéo). Các hàm đồng bộ nuốt lỗi để không làm sập nền; kết quả này cho nơi cần
 * báo thật ("Đồng bộ ngay") biết nửa nào hỏng, thay vì báo xanh khi còn thay đổi chưa lên máy chủ.
 *
 * @property changed số truyện đã thêm / cập nhật / gỡ theo máy chủ.
 * @property pushFailed nửa ĐẨY có hàng hụt.
 * @property error lỗi của nửa KÉO (GET toàn bộ tiến độ); null nếu kéo xong.
 */
class ProgressSyncOutcome(val changed: Int, val pushFailed: Boolean, val error: Throwable?) {
    val succeeded: Boolean get() = !pushFailed && error == null
}

/**
 * Đồng bộ hai chiều tiến độ đọc / kệ / yêu thích với máy chủ.
 *
 * - ĐẨY: mỗi thay đổi cục bộ đặt cờ `syncDirty` (bền vững, nằm trong Room) rồi mới gọi mạng.
 *   Đẩy hụt thì cờ vẫn còn và được thử lại khi ứng dụng ra nền / quay lại / đăng nhập / mở tủ sách.
 * - KÉO: dựng tủ sách trên máy mới từ metadata kèm theo, và hợp nhất theo giờ sửa
 *   (xem [ProgressConflict]).
 *
 * Khách chưa đăng nhập KHÔNG đồng bộ: máy chủ gộp mọi khách vào một user "local", bật đồng bộ
 * sẽ trộn tiến độ của những người khác nhau vào cùng một bản ghi.
 */
internal class ProgressSyncCoordinator(
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val progressApi: ProgressRemote?,
    private val syncStateDao: SyncStateDao?,
    private val scope: CoroutineScope
) {
    private val applier = ProgressRemoteApplier(novelDao, chapterDao, syncStateDao)
    private val flushMutex = Mutex()
    private val scheduleLock = Mutex()
    private val scheduled = HashSet<String>()

    fun canSync(): Boolean = progressApi != null && TruyenCloudApi.authToken.isNotBlank()

    // ── ĐẨY ──────────────────────────────────────────────────────────────

    /**
     * Hẹn một lượt đẩy cho [novelId] sau [PUSH_DEBOUNCE_MS]. Nhiều lần lưu liên tiếp gộp làm một:
     * lượt chờ khi chạy sẽ đọc trạng thái MỚI NHẤT trong Room chứ không dùng giá trị lúc hẹn.
     */
    fun schedulePush(novelId: String) {
        if (!canSync()) return
        scope.launch {
            val first = scheduleLock.withLock { scheduled.add(novelId) }
            if (!first) return@launch
            delay(PUSH_DEBOUNCE_MS)
            // Gỡ khỏi tập chờ TRƯỚC khi đọc Room: lần lưu nào đến sau đó sẽ hẹn một lượt mới.
            scheduleLock.withLock { scheduled.remove(novelId) }
            try {
                val pushed = flushMutex.withLock {
                    val novel = novelDao.getNovelById(novelId)
                    if (novel != null && novel.syncDirty) {
                        pushRow(novel)
                        true
                    } else false
                }
                if (pushed) SyncStatus.reportSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportPushFailure(e)
            }
        }
    }

    /** Đẩy mọi thay đổi còn dirty (và bia mộ xoá khỏi tủ). @return số truyện đã đẩy thành công. */
    suspend fun flushDirty(): Int = flushDirtyDetailed().pushed

    internal suspend fun flushDirtyDetailed(): FlushResult {
        if (!canSync()) return FlushResult(0, false)
        return flushMutex.withLock {
            var pushed = 0
            var failed = false
            try {
                // Bia mộ đẩy hụt chỉ ảnh hưởng chính nó: tiến độ đang dirty vẫn phải được đẩy (trừ khi hết phiên).
                val removals = pushRemovals()
                pushed += removals.pushed
                failed = removals.failed
                var failures = 0
                if (!removals.sessionExpired) for (novel in novelDao.getDirtyNovels()) {
                    try {
                        pushRow(novel)
                        pushed++
                        failures = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed = true
                        reportPushFailure(e)
                        if (ApiErrors.isAuthError(e) || ++failures >= MAX_CONSECUTIVE_FAILURES) break
                    }
                }
                // Báo xanh chỉ khi KHÔNG hàng nào hụt: một hàng hụt rồi hàng sau thành công không được xoá lỗi đã báo.
                if (pushed > 0 && !failed) SyncStatus.reportSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                reportPushFailure(e)
            }
            FlushResult(pushed, failed)
        }
    }

    /** Số thay đổi chưa lên máy chủ: truyện còn dirty + truyện đã xoá khỏi tủ mà chưa báo. */
    suspend fun pendingCount(): Int =
        novelDao.getDirtyNovels().size + (syncStateDao?.listByPrefix(SyncKeys.LIBRARY_REMOVED_PREFIX)?.size ?: 0)

    private fun reportPushFailure(e: Exception, tokenBeforeCall: String? = null) {
        if (ApiErrors.isAuthError(e)) {
            val current = TruyenCloudApi.authToken
            val silentlyRefreshed = tokenBeforeCall != null && current.isNotBlank() && current != tokenBeforeCall
            if (!silentlyRefreshed) SyncStatus.reportSessionExpired()
        } else SyncStatus.reportFailure(ApiErrors.describe(e))
    }

    /** Đẩy một hàng. Ném lỗi nếu mạng/máy chủ hỏng; cờ dirty chỉ được gỡ khi máy chủ xác nhận. */
    private suspend fun pushRow(novel: Novel) {
        val api = progressApi ?: return

        // Truyện vừa thêm vào tủ, chưa đọc chữ nào: đừng để nó ghi đè tiến độ thật đã có trên
        // máy chủ (ví dụ thêm vào tủ trên máy mới trước khi kịp kéo) — nhận bản máy chủ trước.
        if (novel.lastReadChapter <= 0) {
            val remote = api.getProgress(novel.id).let { it.copy(novel_id = it.novel_id.ifBlank { novel.id }) }
            if (remote.current_chapter > 0 && remote.shelf_name != SHELF_REMOVED) {
                applier.adopt(remote)
                return
            }
        }

        var row = novel
        if (row.localStamp <= 0L) {
            // Hàng di trú từ bản cũ chưa có giờ nào. Đây KHÔNG phải thay đổi mới nên không được bịa giờ "bây giờ": kệ /
            // yêu thích cũ sẽ thắng thay đổi MỚI HƠN của máy khác. Giờ tối thiểu thì thua mọi bản có thật của máy chủ
            // (phản hồi "stale" dưới đây nhận bản đó về) và vẫn là một giá trị khác 0 để máy chủ không tự gán giờ nhận.
            row = row.copy(syncUpdatedAt = UNSTAMPED_LEGACY_MS)
            novelDao.stampIfUnset(row.id, UNSTAMPED_LEGACY_MS)
        }
        val sentStamp = row.localStamp
        val scroll = chapterDao.getScrollPosition(row.id, row.lastReadChapter) ?: 0f
        val resp = api.syncProgress(
            novelId = row.id,
            currentChapter = row.lastReadChapter,
            scrollPosition = scroll,
            shelfName = row.shelfName,
            isFavorite = row.isFavorite,
            clientUpdatedAt = sentStamp
        )
        if (resp.isStale) {
            // Máy chủ giữ bản MỚI hơn: nhận bản đó. ProgressRemoteApplier vẫn kiểm lại giờ, nên nếu trong lúc
            // chờ mạng người dùng đọc tiếp (bản cục bộ mới hơn nữa) thì bản cục bộ được giữ.
            resp.progress?.let { applier.apply(it.copy(novel_id = it.novel_id.ifBlank { row.id }), fallback = null) }
        }
        novelDao.clearDirtyIfUnchanged(row.id, row.syncUpdatedAt)
    }

    private class RemovalPush(val pushed: Int, val failed: Boolean, val sessionExpired: Boolean = false)

    /**
     * Báo máy chủ các truyện đã bị xoá khỏi tủ (shelf = "removed") rồi quên bia mộ cục bộ. Mỗi bia mộ được bắt lỗi
     * riêng: một bia mộ cứ hụt mãi (503, 409...) không được chặn các bia mộ khác và việc đẩy tiến độ phía sau.
     */
    private suspend fun pushRemovals(): RemovalPush {
        val api = progressApi ?: return RemovalPush(0, false)
        val dao = syncStateDao ?: return RemovalPush(0, false)
        var pushed = 0
        var failed = false
        var failures = 0
        for (entry in dao.listByPrefix(SyncKeys.LIBRARY_REMOVED_PREFIX)) {
            val novelId = entry.stateKey.removePrefix(SyncKeys.LIBRARY_REMOVED_PREFIX)
            if (novelDao.getNovelById(novelId) != null) {
                dao.delete(entry.stateKey) // đã thêm lại vào tủ sau khi xoá: bia mộ hết nghĩa
                continue
            }
            try {
                api.syncProgress(
                    novelId = novelId,
                    currentChapter = entry.textValue.toIntOrNull() ?: 0,
                    scrollPosition = 0f,
                    shelfName = SHELF_REMOVED,
                    isFavorite = false,
                    clientUpdatedAt = entry.longValue.takeIf { it > 0 } ?: getCurrentEpochMillis()
                )
                // Dù máy chủ trả "success" hay "stale" (có bản mới hơn từ máy khác) thì việc của bia mộ đã xong:
                // kéo về sẽ tự quyết định có dựng lại truyện hay không theo giờ sửa.
                dao.delete(entry.stateKey)
                pushed++
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ApiErrors.isRowRejected(e)) {
                    // Máy chủ từ chối chính bia mộ này (dữ liệu hỏng / xung đột) nên gửi lại mãi cũng vô ích: bỏ, để không
                    // làm trạng thái đồng bộ đỏ vĩnh viễn và tốn một lời gọi hụt ở mọi lượt đẩy.
                    dao.delete(entry.stateKey)
                    continue
                }
                failed = true
                reportPushFailure(e) // bia mộ còn nguyên, lần sau thử lại
                if (ApiErrors.isAuthError(e)) return RemovalPush(pushed, true, sessionExpired = true)
                if (++failures >= MAX_CONSECUTIVE_FAILURES) break
            }
        }
        return RemovalPush(pushed, failed)
    }

    // ── KÉO ──────────────────────────────────────────────────────────────

    /**
     * Kéo tiến độ của MỘT truyện (mở trang chi tiết trên máy khác).
     * @return số chương đã khôi phục nếu khác chương cục bộ, null nếu không đổi gì đáng báo.
     */
    suspend fun pullOne(novelId: String, fallback: Novel?): Int? {
        val api = progressApi ?: return null
        if (novelId.isBlank() || !canSync()) return null
        return try {
            val remote = api.getProgress(novelId).let { it.copy(novel_id = it.novel_id.ifBlank { novelId }) }
            // Máy chủ chưa có bản ghi nào cho truyện này (phản hồi mặc định rỗng).
            if (remote.current_chapter <= 0 && remote.updated_at_ms <= 0L) return null
            val before = novelDao.getNovelById(novelId)?.lastReadChapter ?: 0
            val changed = applier.apply(remote, fallback)
            if (changed && remote.current_chapter > 0 && remote.current_chapter != before) remote.current_chapter else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Không lấy được thì đọc tiếp bằng dữ liệu máy — không chặn đường.
            null
        }
    }

    /**
     * Đồng bộ toàn bộ: đẩy cái còn dirty trước, rồi kéo toàn bộ tủ sách từ máy chủ.
     * @return số truyện đã thêm/cập nhật/gỡ theo máy chủ.
     */
    suspend fun syncAll(): Int = syncAllDetailed().changed

    suspend fun syncAllDetailed(): ProgressSyncOutcome {
        val api = progressApi ?: return ProgressSyncOutcome(0, false, null)
        if (!canSync()) return ProgressSyncOutcome(0, false, null)
        return try {
            val flush = flushDirtyDetailed()
            var changed = 0
            for (remote in api.getAllProgress()) {
                if (applier.apply(remote, fallback = null)) changed++
            }
            // Kéo xong mà nửa đẩy còn hụt thì KHÔNG báo xanh: dòng trạng thái sẽ xoá lỗi trong khi vẫn còn dirty.
            if (!flush.failed) SyncStatus.reportSuccess()
            ProgressSyncOutcome(changed, pushFailed = flush.failed, error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportPushFailure(e)
            ProgressSyncOutcome(0, pushFailed = false, error = e)
        }
    }

    companion object {
        /** Gộp các lần lưu tiến độ liên tiếp (mỗi ~3 giây khi cuộn) thành một lượt đẩy. */
        const val PUSH_DEBOUNCE_MS = 8_000L
        const val MAX_CONSECUTIVE_FAILURES = 3

        /** Giờ gửi cho hàng di trú chưa có giờ nào: nhỏ nhất có thể mà vẫn khác 0 (0 = máy chủ tự gán giờ nhận). */
        const val UNSTAMPED_LEGACY_MS = 1L
    }
}
