package com.truyencloud.shared.data

import com.truyencloud.shared.api.SyncVocabularyRemoteResponse
import com.truyencloud.shared.api.VocabularySyncDataResponse
import com.truyencloud.shared.api.VocabularySyncRemote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Máy chủ đồng bộ từ vựng giả bắt chước `server/routes/vocabulary_sync.py` của MỘT tài khoản: last-write-wins theo
 * `max(updatedAt, deletedAt)`, và — điểm quan trọng — GET chỉ trả dòng thay đổi từ mốc `since` trở đi
 * (`updatedAt >= since` hoặc máy chủ nhận được sau mốc đó), nên một con trỏ cũ trên máy đã bị xoá sẽ bỏ sót mọi dữ liệu cũ.
 */
class FakeVocabularyServer(var clock: Long = 1_790_000_000_000L) : VocabularySyncRemote {
    private val json = Json { encodeDefaults = true }

    private class Stored<T>(val value: T, val changedAt: Long)

    private val words = LinkedHashMap<String, Stored<SavedWord>>()
    private val logs = ArrayList<Stored<ReviewLog>>()

    val pulls = ArrayList<Long>()
    var failWith: Exception? = null

    val wordRows: List<SavedWord> get() = words.values.map { it.value }
    val logRows: List<ReviewLog> get() = logs.map { it.value }

    /** Đặt sẵn dữ liệu trên máy chủ (như thể một máy khác đã đẩy lên), ghi nhận tại [clock]. */
    fun seed(word: SavedWord) { words[word.id] = Stored(word.copy(isSynced = true), clock) }
    fun seed(log: ReviewLog) { logs += Stored(log.copy(isSynced = true), clock) }

    private fun stamp(w: SavedWord) = maxOf(w.updatedAt, w.deletedAt)

    override suspend fun push(words: List<SavedWord>, reviewLogs: List<ReviewLog>): SyncVocabularyRemoteResponse {
        failWith?.let { throw it }
        val stale = ArrayList<String>()
        for (incoming in words) {
            val stored = this.words[incoming.id]?.value
            val wins = stored == null || stamp(incoming) > stamp(stored) ||
                (stamp(incoming) == stamp(stored) && incoming.deletedAt > 0 && stored.deletedAt == 0L)
            if (wins) this.words[incoming.id] = Stored(incoming, clock) else stale += incoming.id
        }
        for (log in reviewLogs) {
            if (logs.none { it.value.wordId == log.wordId && it.value.reviewedAt == log.reviewedAt }) logs += Stored(log, clock)
        }
        return SyncVocabularyRemoteResponse(
            words_synced = words.size, stale_word_ids = stale, words_stale = stale.size,
            logs_synced = reviewLogs.size, server_time_ms = clock
        )
    }

    override suspend fun pull(sinceMs: Long): VocabularySyncDataResponse {
        failWith?.let { throw it }
        pulls += sinceMs
        val changedWords = words.values.filter { sinceMs <= 0L || stamp(it.value) >= sinceMs || it.changedAt >= sinceMs }
        val changedLogs = logs.filter { sinceMs <= 0L || it.value.reviewedAt >= sinceMs || it.changedAt >= sinceMs }
        return VocabularySyncDataResponse(
            words = changedWords.map { el(it.value) },
            review_logs = changedLogs.map { el(it.value) },
            server_time_ms = clock
        )
    }

    private fun el(w: SavedWord): JsonElement = json.encodeToJsonElement(SavedWord.serializer(), w)
    private fun el(l: ReviewLog): JsonElement = json.encodeToJsonElement(ReviewLog.serializer(), l)
}
