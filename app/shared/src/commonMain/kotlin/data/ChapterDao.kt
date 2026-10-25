package com.truyencloud.shared.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Cột của danh sách chương "nhẹ": mọi cột nội dung đổi thành NULL / ''. Danh sách chương
 * được đọc ở MỌI lần mở trình đọc và MỌI lần mở trang chi tiết; bản `SELECT *` kéo cả
 * nguyên văn + bản dịch + bản Anh + song ngữ của hàng trăm chương lên bộ nhớ.
 */
internal const val LIGHT_CHAPTER_COLUMNS =
    "id, novelId, chapterNumber, title, titleVi, url, NULL AS originalContent, " +
        "NULL AS translatedContent, translationEngine, '' AS translatedContentEn, englishLevel, " +
        "'' AS translatedContentBilingual, bilingualLevel, isRead, scrollPosition, updatedAt"

/** Cờ nội dung từng chương (xem [ChapterFlags]). */
internal const val CHAPTER_FLAG_COLUMNS =
    "chapterNumber, " +
        "(originalContent IS NOT NULL AND originalContent != '') AS hasOriginal, " +
        "(translatedContent IS NOT NULL AND translatedContent != '') AS hasTranslation, " +
        "(translatedContent IS NOT NULL AND translatedContent != '' " +
        "AND translationEngine = '$ENGINE_GOOGLE_FALLBACK') AS isProvisional, " +
        "(translatedContentEn != '') AS hasEnglish, " +
        "(translatedContentBilingual != '') AS hasBilingual"

/**
 * "Sẵn sàng đọc offline" (chế độ tiếng Việt) = có BẢN DỊCH tiếng Việt. Chương chỉ có văn bản
 * gốc (vd. bản tải trước ở chế độ thô) không được tính, nếu không tải hàng loạt sẽ bỏ qua
 * chúng và mất mạng thì người đọc thấy nguyên tiếng Trung.
 */
internal const val HAS_TRANSLATION_SQL = "(translatedContent IS NOT NULL AND translatedContent != '')"

/** Có bất kỳ nội dung nào (kể cả chỉ bản gốc / chỉ bản Anh) — dùng cho thống kê dung lượng. */
internal const val HAS_ANY_CONTENT_SQL =
    "((originalContent IS NOT NULL AND originalContent != '') OR $HAS_TRANSLATION_SQL " +
        "OR translatedContentEn != '' OR translatedContentBilingual != '')"

@Dao
interface ChapterDao {
    /** Bản ĐẦY ĐỦ nội dung — chỉ dùng khi thực sự cần chép nội dung (hiếm). Danh sách thì dùng bản nhẹ. */
    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY chapterNumber ASC")
    fun getChaptersForNovel(novelId: String): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY chapterNumber ASC")
    suspend fun getChaptersForNovelOnce(novelId: String): List<Chapter>

    /** Danh sách chương KHÔNG nội dung (originalContent/translatedContent = NULL, bản Anh/song ngữ = ''). */
    @Query("SELECT $LIGHT_CHAPTER_COLUMNS FROM chapters WHERE novelId = :novelId ORDER BY chapterNumber ASC")
    fun getChaptersForNovelLight(novelId: String): Flow<List<Chapter>>

    @Query("SELECT $LIGHT_CHAPTER_COLUMNS FROM chapters WHERE novelId = :novelId ORDER BY chapterNumber ASC")
    suspend fun getChapterListLight(novelId: String): List<Chapter>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND chapterNumber = :chapterNumber LIMIT 1")
    suspend fun getChapter(novelId: String, chapterNumber: Int): Chapter?

    @Query("SELECT MAX(chapterNumber) FROM chapters WHERE novelId = :novelId")
    suspend fun getMaxChapterNumber(novelId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapter(chapter: Chapter)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<Chapter>)

    /** Chèn các chương chưa có; chương đã có giữ NGUYÊN (nội dung, bản dịch, vị trí cuộn, đã đọc). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChaptersIfAbsent(chapters: List<Chapter>): List<Long>

    /** Làm mới mục lục: chỉ sửa tên/đường dẫn, không đụng nội dung hay tiến độ. */
    @Query(
        """UPDATE chapters SET title = :title, titleVi = :titleVi, url = :url
           WHERE novelId = :novelId AND chapterNumber = :chapterNumber"""
    )
    suspend fun updateChapterMetadata(novelId: String, chapterNumber: Int, title: String, titleVi: String, url: String): Int

    /**
     * Ghi nội dung vừa tải từ máy chủ mà KHÔNG chép đè vị trí cuộn, cờ đã đọc hay bản
     * Anh/song ngữ (bản cũ REPLACE cả hàng nên một lần cuộn lưu giữa chừng bị mất).
     * Tên rỗng / gốc rỗng thì giữ giá trị đang có.
     */
    @Query(
        """UPDATE chapters SET
            title = CASE WHEN :title != '' THEN :title ELSE title END,
            titleVi = CASE WHEN :titleVi != '' THEN :titleVi ELSE titleVi END,
            originalContent = CASE WHEN :original != '' THEN :original ELSE originalContent END,
            translatedContent = :translated,
            translationEngine = :engine
           WHERE novelId = :novelId AND chapterNumber = :chapterNumber"""
    )
    suspend fun updateFetchedContent(
        novelId: String,
        chapterNumber: Int,
        title: String,
        titleVi: String,
        original: String,
        translated: String?,
        engine: String?
    ): Int

    @Query(
        """UPDATE chapters SET
            translatedContent = :translated,
            translationEngine = :engine,
            titleVi = CASE WHEN :titleVi != '' THEN :titleVi ELSE titleVi END
           WHERE novelId = :novelId AND chapterNumber = :chapterNumber"""
    )
    suspend fun updateTranslation(
        novelId: String,
        chapterNumber: Int,
        translated: String,
        engine: String = "ANTIGRAVITY",
        titleVi: String = ""
    )

    /** Lưu bản tiếng Anh kèm cấp độ CEFR để đọc lại offline mà không tốn thêm lời gọi AI. */
    @Query(
        """
        UPDATE chapters SET translatedContentEn = :content, englishLevel = :level
        WHERE novelId = :novelId AND chapterNumber = :chapterNumber
        """
    )
    suspend fun updateEnglishTranslation(novelId: String, chapterNumber: Int, content: String, level: String)

    @Query(
        """
        UPDATE chapters SET translatedContentBilingual = :content, bilingualLevel = :level
        WHERE novelId = :novelId AND chapterNumber = :chapterNumber
        """
    )
    suspend fun updateBilingualTranslation(novelId: String, chapterNumber: Int, content: String, level: String)

    /**
     * Lưu vị trí cuộn. Cờ "đã đọc" CHỈ được bật khi [markRead] (đã cuộn gần hết chương) —
     * bản cũ bật isRead ở MỌI lần lưu, kể cả khi vừa mở chương với scroll = 0.
     * Trả 0 nếu hàng chưa tồn tại.
     */
    @Query(
        """UPDATE chapters SET scrollPosition = :scroll,
           isRead = CASE WHEN :markRead = 1 THEN 1 ELSE isRead END
           WHERE novelId = :novelId AND chapterNumber = :chapterNumber"""
    )
    suspend fun updateReadingProgress(novelId: String, chapterNumber: Int, scroll: Float, markRead: Boolean): Int

    @Query("UPDATE chapters SET isRead = 1 WHERE novelId = :novelId AND chapterNumber = :chapterNumber")
    suspend fun markRead(novelId: String, chapterNumber: Int): Int

    // ── Cờ nội dung & chương đã tải ──────────────────────────────────────

    @Query("SELECT $CHAPTER_FLAG_COLUMNS FROM chapters WHERE novelId = :novelId ORDER BY chapterNumber ASC")
    fun observeChapterFlags(novelId: String): Flow<List<ChapterFlags>>

    @Query("SELECT $CHAPTER_FLAG_COLUMNS FROM chapters WHERE novelId = :novelId ORDER BY chapterNumber ASC")
    suspend fun getChapterFlags(novelId: String): List<ChapterFlags>

    @Query("SELECT $CHAPTER_FLAG_COLUMNS FROM chapters WHERE novelId = :novelId AND chapterNumber = :chapterNumber LIMIT 1")
    suspend fun getChapterFlag(novelId: String, chapterNumber: Int): ChapterFlags?

    /** Chương đã tải và SẴN SÀNG đọc offline (có bản dịch) — không tính chương chỉ có bản gốc. */
    @Query("SELECT chapterNumber FROM chapters WHERE novelId = :novelId AND $HAS_TRANSLATION_SQL ORDER BY chapterNumber ASC")
    fun getDownloadedChapterNumbers(novelId: String): Flow<List<Int>>

    @Query("SELECT COUNT(*) FROM chapters WHERE novelId = :novelId AND $HAS_TRANSLATION_SQL")
    suspend fun getDownloadedChapterCount(novelId: String): Int

    @Query("DELETE FROM chapters WHERE novelId = :novelId")
    suspend fun deleteChaptersForNovel(novelId: String)

    @Query("SELECT scrollPosition FROM chapters WHERE novelId = :novelId AND chapterNumber = :chapterNumber LIMIT 1")
    suspend fun getScrollPosition(novelId: String, chapterNumber: Int): Float?

    /**
     * Giải phóng nội dung chương nhưng GIỮ mục lục, tiến độ đọc và vị trí cuộn.
     * Người dùng mất bản tải offline nhưng không mất chỗ đang đọc.
     */
    @Query(
        """
        UPDATE chapters SET
            originalContent = NULL,
            translatedContent = NULL,
            translatedContentEn = '',
            englishLevel = '',
            translatedContentBilingual = '',
            bilingualLevel = ''
        WHERE originalContent IS NOT NULL
           OR translatedContent IS NOT NULL
           OR translatedContentEn != ''
           OR translatedContentBilingual != ''
        """
    )
    suspend fun clearAllContent(): Int

    /** Ước lượng dung lượng nội dung chương đang chiếm, theo byte. */
    @Query(
        """
        SELECT COALESCE(SUM(
            LENGTH(CAST(COALESCE(originalContent, '') AS BLOB)) +
            LENGTH(CAST(COALESCE(translatedContent, '') AS BLOB)) +
            LENGTH(CAST(translatedContentEn AS BLOB)) +
            LENGTH(CAST(translatedContentBilingual AS BLOB))
        ), 0) FROM chapters
        """
    )
    suspend fun estimateContentBytes(): Long?

    // ── Per-novel offline storage management ────────────────────────────

    /** Dung lượng nội dung của một truyện cụ thể (byte). */
    @Query(
        """
        SELECT COALESCE(SUM(
            LENGTH(CAST(COALESCE(originalContent, '') AS BLOB)) +
            LENGTH(CAST(COALESCE(translatedContent, '') AS BLOB)) +
            LENGTH(CAST(translatedContentEn AS BLOB)) +
            LENGTH(CAST(translatedContentBilingual AS BLOB))
        ), 0) FROM chapters WHERE novelId = :novelId
        """
    )
    suspend fun estimateContentBytesForNovel(novelId: String): Long

    /** Xoá nội dung offline của MỘT truyện, giữ mục lục và tiến độ. */
    @Query(
        """
        UPDATE chapters SET
            originalContent = NULL,
            translatedContent = NULL,
            translatedContentEn = '',
            englishLevel = '',
            translatedContentBilingual = '',
            bilingualLevel = ''
        WHERE novelId = :novelId
          AND (originalContent IS NOT NULL
               OR translatedContent IS NOT NULL
               OR translatedContentEn != ''
               OR translatedContentBilingual != '')
        """
    )
    suspend fun clearContentForNovel(novelId: String): Int

    /** Liệt kê novelId có ít nhất 1 chương đã cache (kể cả chỉ bản gốc — chúng vẫn chiếm dung lượng). */
    @Query(
        """
        SELECT novelId, COUNT(*) AS cachedCount
        FROM chapters
        WHERE $HAS_ANY_CONTENT_SQL
        GROUP BY novelId
        ORDER BY cachedCount DESC
        """
    )
    suspend fun getCachedNovelSummaries(): List<CachedNovelSummary>
}

/** Dùng cho StorageManager — tóm tắt truyện đã cache. */
data class CachedNovelSummary(
    val novelId: String,
    val cachedCount: Int
)
