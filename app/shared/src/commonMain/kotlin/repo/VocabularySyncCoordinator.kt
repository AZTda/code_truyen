package com.truyencloud.shared.repo

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.api.VocabularySyncRemote
import com.truyencloud.shared.api.VocabularySyncHttpException
import com.truyencloud.shared.data.ReviewLog
import com.truyencloud.shared.data.ReviewLogDao
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.SavedWordDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

sealed interface VocabularySyncResult {
    data class Success(
        val wordsPushed: Int,
        val logsPushed: Int,
        val wordsPulled: Int,
        val logsPulled: Int
    ) : VocabularySyncResult

    /** Chưa đăng nhập — KHÔNG phải thành công (trước đây trả về "0 từ, 0 lượt ôn" và báo xanh). */
    data object NotLoggedIn : VocabularySyncResult

    data class Failure(val message: String, val sessionExpired: Boolean = false) : VocabularySyncResult
}

/**
 * Đồng bộ 2 chiều từ vựng + nhật ký ôn với máy chủ cá nhân (hợp đồng C7).
 *
 * Đẩy: chỉ các dòng đổi (isSynced = 0, hoặc updatedAt / deletedAt sau mốc lần trước), chia lô
 * <= [MAX_WORDS_PER_REQUEST] từ và <= [MAX_LOGS_PER_REQUEST] log mỗi yêu cầu.
 * Kéo: `GET ?since=<mốc>`; gộp theo [VocabularySyncMerge]; lưu `server_time_ms` làm mốc lần sau. Mốc nằm trong Room theo từng
 * tài khoản ([VocabularySyncStore]) nên bị xoá cùng dữ liệu; máy không còn từ / lượt ôn nào thì luôn kéo từ đầu.
 */
class VocabularySyncCoordinator(
    private val savedWordDao: SavedWordDao,
    private val reviewLogDao: ReviewLogDao?,
    private val api: VocabularySyncRemote,
    private val store: VocabularySyncStore
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** @param userInitiated người dùng bấm nút (báo "Chưa đăng nhập" vào SyncStatus); false = tự động, im lặng. */
    suspend fun sync(userInitiated: Boolean): VocabularySyncResult {
        val token = TruyenCloudApi.authToken
        if (token.isBlank()) {
            if (userInitiated) SyncStatus.reportFailure(MSG_NOT_LOGGED_IN)
            return VocabularySyncResult.NotLoggedIn
        }
        val account = SyncAccount.keyFor(token)
        return mutex.withLock {
            try {
                val hasLocalData = hasLocalData()
                // Máy trống mà con trỏ báo "đã kéo tới mốc X": dữ liệu đã bị xoá SAU lần kéo ấy (xoá dữ liệu trên máy,
                // đổi tài khoản) và máy chủ sẽ chỉ trả phần đổi sau X -> kéo lại từ đầu để khôi phục toàn bộ.
                val stored = store.cursor(account)
                val cursor = if (hasLocalData) stored else 0L
                val push = pushChanges(cursor)
                // Máy chủ báo có từ "cũ hơn bản của nó" mà mốc since có thể không phủ tới -> kéo lại toàn bộ lượt này.
                val pull = pullAndMerge(if (push.staleIds.isNotEmpty()) 0L else cursor)
                store.saveCursor(account, pull.serverTimeMs)
                SyncStatus.reportSuccess()
                VocabularySyncResult.Success(push.words, push.logs, pull.words, pull.logs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VocabularySyncHttpException) {
                if (e.status == 401) {
                    val refreshedToken = TruyenCloudApi.authToken
                    val silentlyRefreshed = refreshedToken.isNotBlank() && refreshedToken != token
                    if (!silentlyRefreshed) SyncStatus.reportSessionExpired()
                    VocabularySyncResult.Failure("Phiên đăng nhập đã hết hạn", sessionExpired = !silentlyRefreshed)
                } else {
                    val msg = "Lỗi máy chủ (${e.status}): ${e.message}"
                    SyncStatus.reportFailure(msg)
                    VocabularySyncResult.Failure(msg)
                }
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "Không kết nối được máy chủ"
                SyncStatus.reportFailure(msg)
                VocabularySyncResult.Failure(msg)
            }
        }
    }

    /** Có ít nhất một từ (kể cả bia mộ) hoặc một lượt ôn trên máy. */
    private suspend fun hasLocalData(): Boolean =
        savedWordDao.countRowsIncludingDeleted() > 0 || (reviewLogDao?.countAll() ?: 0) > 0

    private class PushStats(val words: Int, val logs: Int, val staleIds: Set<String>)
    private class PullStats(val words: Int, val logs: Int, val serverTimeMs: Long)

    private suspend fun pushChanges(cursor: Long): PushStats {
        val wordChunks = savedWordDao.getChangedForSync(cursor).chunked(MAX_WORDS_PER_REQUEST)
        var logs = reviewLogDao?.getUnsynced(MAX_LOGS_PER_REQUEST).orEmpty()
        val stale = LinkedHashSet<String>()
        var pushedWords = 0
        var pushedLogs = 0
        var index = 0
        var guard = 0
        while ((index < wordChunks.size || logs.isNotEmpty()) && guard++ < MAX_REQUESTS) {
            val words = wordChunks.getOrElse(index) { emptyList() }
            val res = api.push(words, logs)
            stale += res.stale_word_ids
            // Chỉ hạ cờ khi dòng chưa bị sửa trong lúc gửi — nếu không, lần ôn vừa xong sẽ không bao giờ được đẩy.
            words.forEach { savedWordDao.markSyncedIfUnchanged(it.id, it.updatedAt, it.deletedAt) }
            if (logs.isNotEmpty()) logs.map { it.id }.chunked(MARK_CHUNK).forEach { reviewLogDao?.markSynced(it) }
            pushedWords += words.size
            pushedLogs += logs.size
            index++
            logs = reviewLogDao?.getUnsynced(MAX_LOGS_PER_REQUEST).orEmpty()
        }
        return PushStats(pushedWords, pushedLogs, stale)
    }

    private suspend fun pullAndMerge(since: Long): PullStats {
        val remote = api.pull(since)
        var words = 0
        for (element in remote.words) {
            val word = decode(SavedWord.serializer(), element) ?: continue
            if (applyRemoteWord(word)) words++
        }
        return PullStats(words, mergeLogs(remote.review_logs), remote.server_time_ms)
    }

    private suspend fun applyRemoteWord(remote: SavedWord): Boolean {
        if (remote.id.isBlank() || remote.word.isBlank()) return false
        val local = savedWordDao.getByIdIncludingDeleted(remote.id)
        if (!VocabularySyncMerge.remoteWins(local, remote)) {
            if (local != null && VocabularySyncMerge.stamp(local) == VocabularySyncMerge.stamp(remote)) {
                savedWordDao.markSyncedIfUnchanged(local.id, local.updatedAt, local.deletedAt)
            }
            return false
        }
        // Chỉ mục unique trên `word`: hai id khác nhau cùng một chữ thì giữ bản mới hơn.
        val clash = savedWordDao.getWordIncludingDeleted(remote.word)
        if (clash != null && clash.id != remote.id) {
            if (!VocabularySyncMerge.remoteBeatsClash(clash, remote)) return false
            savedWordDao.purge(clash.id)
        }
        savedWordDao.upsertWord(remote.copy(isSynced = true))
        return true
    }

    private suspend fun mergeLogs(elements: List<JsonElement>): Int {
        val dao = reviewLogDao ?: return 0
        if (elements.isEmpty()) return 0
        val remote = elements.mapNotNull { decode(ReviewLog.serializer(), it) }
        val fresh = VocabularySyncMerge.newLogs(remote, dao.allKeys().toHashSet())
        fresh.chunked(MARK_CHUNK).forEach { dao.insertAll(it) }
        return fresh.size
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, element: JsonElement): T? =
        runCatching { json.decodeFromJsonElement(serializer, element) }.getOrNull()

    companion object {
        /** Nhỏ hơn giới hạn 500 / 2000 của máy chủ: từ mang nhiều JSON (kịch bản, câu tự đặt) nên lô 500 từ có thể tới vài MB. */
        const val MAX_WORDS_PER_REQUEST = 200
        const val MAX_LOGS_PER_REQUEST = 1000
        private const val MAX_REQUESTS = 500
        private const val MARK_CHUNK = 400
        const val MSG_NOT_LOGGED_IN = "Chưa đăng nhập"
    }
}
