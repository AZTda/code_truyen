package com.truyencloud.shared.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Điều kiện "lượt ôn đủ tư cách tính tỉ lệ nhớ": KHÔNG phải luyện tập, và thẻ đang ở trạng thái
 * Ôn tập (2) — thẻ mới / đang học bước ngắn chưa có gì để "quên". Log cũ (stateBefore = -1)
 * dùng stabilityBefore > 0 làm xấp xỉ "đã từng học".
 */
private const val RETENTION_ELIGIBLE =
    "isPractice = 0 AND (stateBefore = 2 OR (stateBefore = -1 AND stabilityBefore > 0))"

/** Khoá tự nhiên của một lượt ôn — dùng khử trùng khi gộp log từ máy chủ. */
data class ReviewLogKey(val wordId: String, val reviewedAt: Long)

@Dao
interface ReviewLogDao {

    @Insert
    suspend fun insert(log: ReviewLog): Long

    @Insert
    suspend fun insertAll(logs: List<ReviewLog>)

    @Query("SELECT COUNT(*) FROM review_logs WHERE wordId = :wordId AND reviewedAt = :reviewedAt")
    suspend fun countByKey(wordId: String, reviewedAt: Long): Int

    /** Toàn bộ khoá (wordId, reviewedAt) đã có — một lần đọc để lọc cả lô log kéo về. */
    @Query("SELECT wordId AS wordId, reviewedAt AS reviewedAt FROM review_logs")
    suspend fun allKeys(): List<ReviewLogKey>

    /** Xoá các dòng trùng (wordId, reviewedAt) do đồng bộ cũ nhân đôi, giữ dòng có id nhỏ nhất. */
    @Query("DELETE FROM review_logs WHERE id NOT IN (SELECT MIN(id) FROM review_logs GROUP BY wordId, reviewedAt)")
    suspend fun deleteDuplicates(): Int

    @Query("SELECT * FROM review_logs ORDER BY reviewedAt DESC LIMIT :limit")
    suspend fun getAllLogsOnce(limit: Int = 1000): List<ReviewLog>

    @Query("SELECT * FROM review_logs ORDER BY reviewedAt DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<ReviewLog>>

    /** Log chưa đẩy lên máy chủ, cũ nhất trước. */
    @Query("SELECT * FROM review_logs WHERE isSynced = 0 ORDER BY reviewedAt ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int): List<ReviewLog>

    /** Số log chưa đẩy lên máy chủ — để cảnh báo trước khi xoá dữ liệu / đổi tài khoản. */
    @Query("SELECT COUNT(*) FROM review_logs WHERE isSynced = 0")
    suspend fun countUnsynced(): Int

    @Query("UPDATE review_logs SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    /** Điểm các lượt ôn thật của một từ theo thứ tự thời gian — phát lại để tính lại độ khó. */
    @Query("SELECT rating FROM review_logs WHERE wordId = :wordId AND isPractice = 0 ORDER BY reviewedAt ASC")
    suspend fun ratingsForWord(wordId: String): List<Int>

    // ---- Đếm theo ngày học ----------------------------------------------

    /** Số lượt ôn (mọi loại, kể cả luyện tập) trong một ngày YYYY-MM-DD. */
    @Query("SELECT COUNT(*) FROM review_logs WHERE dateKey = :dateKey")
    suspend fun countForDateOnce(dateKey: String): Int

    /** Số lượt ôn CÓ đổi lịch FSRS kể từ mốc (đầu ngày học) — đếm vào hạn mức và mục tiêu ngày. */
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :sinceEpoch AND isPractice = 0")
    suspend fun countScheduledSince(sinceEpoch: Long): Int

    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :sinceEpoch AND isPractice = 0")
    fun observeScheduledSince(sinceEpoch: Long): Flow<Int>

    /** Số thẻ MỚI được giới thiệu (lần học đầu tiên) kể từ mốc — để trừ vào hạn mức thẻ mới mỗi ngày. */
    @Query(
        """
        SELECT COUNT(DISTINCT wordId) FROM review_logs
        WHERE reviewedAt >= :sinceEpoch AND isPractice = 0
          AND (stateBefore = 0 OR (stateBefore = -1 AND stabilityBefore = 0))
        """
    )
    suspend fun countNewIntroducedSince(sinceEpoch: Long): Int

    @Query(
        """
        SELECT COUNT(DISTINCT wordId) FROM review_logs
        WHERE reviewedAt >= :sinceEpoch AND isPractice = 0
          AND (stateBefore = 0 OR (stateBefore = -1 AND stabilityBefore = 0))
        """
    )
    fun observeNewIntroducedSince(sinceEpoch: Long): Flow<Int>

    /** Tổng số lượt ôn từ trước tới giờ (COUNT, không phải getAllLogsOnce(500).size như trước). */
    @Query("SELECT COUNT(*) FROM review_logs")
    suspend fun countAll(): Int

    // ---- Hoạt động / chuỗi ngày --------------------------------------------

    /** Các ngày ĐÃ CÓ hoạt động ôn tập (kể cả luyện tập), mới nhất trước — tính chuỗi ngày thật. */
    @Query("SELECT DISTINCT dateKey FROM review_logs WHERE dateKey != '' ORDER BY dateKey DESC LIMIT :limit")
    fun activeDates(limit: Int = 400): Flow<List<String>>

    @Query("SELECT DISTINCT dateKey FROM review_logs WHERE dateKey != '' ORDER BY dateKey DESC LIMIT :limit")
    suspend fun activeDatesOnce(limit: Int = 400): List<String>

    // ---- Tỉ lệ nhớ -----------------------------------------------------------

    /**
     * Tỉ lệ nhớ: số lượt trả lời đúng (rating >= 2) trên tổng số lượt đủ tư cách
     * (thẻ đang ở trạng thái Ôn tập và KHÔNG phải luyện tập), tính từ một mốc.
     */
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :sinceEpoch AND rating >= 2 AND $RETENTION_ELIGIBLE")
    suspend fun successCountSince(sinceEpoch: Long): Int

    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :sinceEpoch AND $RETENTION_ELIGIBLE")
    suspend fun totalCountSince(sinceEpoch: Long): Int

    /** Số lượt ôn theo từng ngày trong khoảng — dữ liệu cho biểu đồ 30 ngày (chỉ lượt đủ tư cách). */
    @Query(
        """
        SELECT dateKey AS dateKey,
               COUNT(*) AS total,
               SUM(CASE WHEN rating >= 2 THEN 1 ELSE 0 END) AS correct
        FROM review_logs
        WHERE dateKey >= :fromDateKey AND dateKey != '' AND $RETENTION_ELIGIBLE
        GROUP BY dateKey
        ORDER BY dateKey ASC
        """
    )
    fun dailyStats(fromDateKey: String): Flow<List<DailyReviewStat>>

    @Query("DELETE FROM review_logs WHERE wordId = :wordId")
    suspend fun deleteForWord(wordId: String)

    @Query("DELETE FROM review_logs WHERE id = :id")
    suspend fun deleteById(id: Long)
}

data class DailyReviewStat(
    val dateKey: String,
    val total: Int,
    val correct: Int
) {
    val accuracy: Double get() = if (total <= 0) 0.0 else correct.toDouble() / total
}
