package com.truyencloud.shared.data

import androidx.room.Query

/**
 * Truy vấn phục vụ đồng bộ từ vựng, tách khỏi [SavedWordDao] cho gọn tệp ([SavedWordDao] thừa kế interface này).
 *
 * Con trỏ kéo của từ vựng nằm trong bảng `sync_state` CHUNG cơ sở dữ liệu với `saved_words` / `review_logs`
 * (lý do xem [SyncState]): `LocalDataMaintenance.wipeUserData()` xoá cả hai trong cùng một giao dịch nên không
 * thể còn cảnh "con trỏ nói đã kéo tới mốc X trong khi từ vựng đã bị xoá" — khi đó máy chủ chỉ trả phần thay đổi
 * sau mốc X và từ vựng cũ không bao giờ được tải lại.
 * Truy vấn nằm ở đây (thay vì dùng [SyncStateDao]) vì VocabularyRepository chỉ được nối sẵn SavedWordDao / ReviewLogDao.
 */
interface VocabularySyncQueries {

    /** Số dòng trong `saved_words`, KỂ CẢ bia mộ — máy "trống" nghĩa là không có dòng nào. */
    @Query("SELECT COUNT(*) FROM saved_words")
    suspend fun countRowsIncludingDeleted(): Int

    /** Số dòng (kể cả bia mộ) đã có từ trước mốc [ts] — bằng chứng rằng dữ liệu trên máy thật sự được đồng bộ tới mốc đó. */
    @Query("SELECT COUNT(*) FROM saved_words WHERE updatedAt > 0 AND updatedAt <= :ts")
    suspend fun countWordsUpdatedUpTo(ts: Long): Int

    /** Số từ có thay đổi chưa đẩy lên máy chủ (isSynced = 0), gồm cả bia mộ. */
    @Query("SELECT COUNT(*) FROM saved_words WHERE isSynced = 0")
    suspend fun countUnsyncedWords(): Int

    /** Con trỏ kéo đã lưu theo [SyncKeys.vocabCursor]; null = chưa có. */
    @Query("SELECT longValue FROM sync_state WHERE stateKey = :key LIMIT 1")
    suspend fun getSyncCursor(key: String): Long?

    @Query("INSERT OR REPLACE INTO sync_state (stateKey, longValue, textValue) VALUES (:key, :value, '')")
    suspend fun putSyncCursor(key: String, value: Long)

    /** Dòng cần đẩy lên: chưa đồng bộ (isSynced = 0), hoặc mọi dòng có dấu thời gian khi đẩy từ đầu (sinceEpoch <= 0). */
    @Query(
        """
        SELECT * FROM saved_words
        WHERE isSynced = 0 OR (:sinceEpoch <= 0 AND (updatedAt > 0 OR deletedAt > 0))
        ORDER BY updatedAt ASC
        """
    )
    suspend fun getChangedForSync(sinceEpoch: Long): List<SavedWord>
}
