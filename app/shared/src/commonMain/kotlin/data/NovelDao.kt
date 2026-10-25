package com.truyencloud.shared.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelDao {
    @Query("SELECT * FROM novels ORDER BY lastReadAt DESC")
    fun getAllNovels(): Flow<List<Novel>>

    @Query("SELECT * FROM novels WHERE shelfName = :shelf ORDER BY lastReadAt DESC")
    fun getNovelsByShelf(shelf: String): Flow<List<Novel>>

    @Query("SELECT * FROM novels WHERE id = :id LIMIT 1")
    suspend fun getNovelById(id: String): Novel?

    @Query("SELECT * FROM novels WHERE id = :id LIMIT 1")
    fun observeNovel(id: String): Flow<Novel?>

    @Query("SELECT * FROM novels WHERE isFavorite = 1 ORDER BY lastReadAt DESC")
    fun getFavoriteNovels(): Flow<List<Novel>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNovel(novel: Novel)

    /** Chèn nếu chưa có; trả -1 khi đã tồn tại (không đụng dữ liệu người dùng). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(novel: Novel): Long

    /**
     * Chỉ cập nhật metadata từ nguồn — giữ nguyên tiến độ, kệ, yêu thích.
     * Trả số hàng đổi: 0 nghĩa là truyện không thuộc tủ sách (KHÔNG chèn mới).
     */
    @Query(
        """UPDATE novels SET title = :title, titleVi = :titleVi, author = :author, url = :url,
           coverUrl = :coverUrl, sourceId = :sourceId, totalChapters = :totalChapters,
           description = :description, status = :status WHERE id = :id"""
    )
    suspend fun updateMetadata(
        id: String, title: String, titleVi: String, author: String, url: String,
        coverUrl: String, sourceId: String, totalChapters: Int, description: String, status: String
    ): Int

    @Query("UPDATE novels SET shelfName = :shelf, syncUpdatedAt = :stamp, syncDirty = 1 WHERE id = :id")
    suspend fun updateShelf(id: String, shelf: String, stamp: Long): Int

    @Query("UPDATE novels SET isFavorite = :favorite, syncUpdatedAt = :stamp, syncDirty = 1 WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, stamp: Long): Int

    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun deleteNovelById(id: String)

    @Query("DELETE FROM novels")
    suspend fun deleteAll()

    /** Đọc tiếp ở máy này: cập nhật chương, giờ đọc và đánh dấu "chưa đẩy lên máy chủ". */
    @Query(
        """UPDATE novels SET lastReadChapter = :chapter, lastReadAt = :timestamp,
           syncUpdatedAt = :timestamp, syncDirty = 1 WHERE id = :id"""
    )
    suspend fun updateReadingProgress(id: String, chapter: Int, timestamp: Long): Int

    /** So tac pham trong tu sach — the thong ke truoc day hien cung mot hang so "12". */
    @Query("SELECT COUNT(*) FROM novels")
    suspend fun countNovels(): Int

    // ── Đồng bộ tiến độ ──────────────────────────────────────────────────

    @Query("SELECT * FROM novels WHERE syncDirty = 1")
    suspend fun getDirtyNovels(): List<Novel>

    /** Hàng rất cũ chưa có giờ nào (cả syncUpdatedAt lẫn lastReadAt = 0): gắn giờ trước khi đẩy lên. */
    @Query("UPDATE novels SET syncUpdatedAt = :stamp WHERE id = :id AND syncUpdatedAt = 0 AND lastReadAt = 0")
    suspend fun stampIfUnset(id: String, stamp: Long): Int

    /**
     * Gỡ cờ "chưa đẩy" CHỈ khi hàng chưa bị sửa tiếp trong lúc đang gọi mạng
     * (syncUpdatedAt vẫn đúng mốc đã gửi) — nếu không, một lần đọc tiếp diễn ra giữa
     * chừng sẽ bị coi là đã đồng bộ và không bao giờ được gửi.
     */
    @Query("UPDATE novels SET syncDirty = 0 WHERE id = :id AND syncUpdatedAt = :stamp")
    suspend fun clearDirtyIfUnchanged(id: String, stamp: Long): Int

    /** Áp bản máy chủ thắng cuộc: không đánh dấu dirty vì đó chính là trạng thái của máy chủ. */
    @Query(
        """UPDATE novels SET lastReadChapter = :chapter, lastReadAt = :stamp, shelfName = :shelf,
           isFavorite = :favorite, syncUpdatedAt = :stamp, syncDirty = 0 WHERE id = :id"""
    )
    suspend fun applyRemoteProgress(id: String, chapter: Int, stamp: Long, shelf: String, favorite: Boolean): Int
}
