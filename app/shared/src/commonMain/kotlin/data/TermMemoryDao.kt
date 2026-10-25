package com.truyencloud.shared.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TermMemoryDao {

    /**
     * Bảng thuật ngữ gửi kèm khi dịch: gồm thuật ngữ riêng của truyện và thuật ngữ toàn cục.
     *
     * Thứ tự rất quan trọng vì phía máy chủ thay thế chuỗi tuần tự: từ DÀI phải đi trước
     * từ NGẮN, nếu không "麻省" sẽ ăn mất một nửa của "麻省理工" và phần còn lại thành rác.
     * Từ được ghim và từ hay tra được ưu tiên khi bảng bị cắt bớt vì quá dài.
     */
    @Query(
        """
        SELECT * FROM term_memories
        WHERE novelId = :novelId OR novelId = ''
        ORDER BY pinned DESC, hitCount DESC, LENGTH(source) DESC
        LIMIT :limit
        """
    )
    suspend fun getGlossaryFor(novelId: String, limit: Int): List<TermMemory>

    /** Danh sách để hiển thị và sửa trong màn hình quản lý thuật ngữ. */
    @Query("SELECT * FROM term_memories WHERE novelId = :novelId OR novelId = '' ORDER BY updatedEpoch DESC")
    fun observeForNovel(novelId: String): Flow<List<TermMemory>>

    @Query("SELECT * FROM term_memories WHERE novelId = :novelId AND source = :source LIMIT 1")
    suspend fun find(novelId: String, source: String): TermMemory?

    /** Tìm cả bản toàn cục, để không lưu trùng khi từ đã có ở phạm vi chung. */
    @Query("SELECT * FROM term_memories WHERE (novelId = :novelId OR novelId = '') AND source = :source ORDER BY novelId DESC LIMIT 1")
    suspend fun findIncludingGlobal(novelId: String, source: String): TermMemory?

    @Upsert
    suspend fun upsert(term: TermMemory)

    @Upsert
    suspend fun upsertAll(terms: List<TermMemory>)

    @Query("UPDATE term_memories SET pinned = :pinned, updatedEpoch = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long)

    @Query("UPDATE term_memories SET target = :target, updatedEpoch = :now WHERE id = :id")
    suspend fun updateTarget(id: String, target: String, now: Long)

    @Query("DELETE FROM term_memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM term_memories WHERE novelId = :novelId")
    suspend fun deleteAllForNovel(novelId: String)

    @Query("SELECT COUNT(*) FROM term_memories WHERE novelId = :novelId OR novelId = ''")
    suspend fun countForNovel(novelId: String): Int
}
