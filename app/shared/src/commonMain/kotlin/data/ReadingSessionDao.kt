package com.truyencloud.shared.data

import androidx.room.*

@Dao
interface ReadingSessionDao {
    @Query("SELECT * FROM reading_sessions WHERE date = :date LIMIT 1")
    suspend fun getSessionByDate(date: String): ReadingSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ReadingSession)

    @Query("UPDATE reading_sessions SET minutesRead = minutesRead + :minutes, chaptersRead = chaptersRead + :chapters, lastUpdatedAt = :timestamp WHERE date = :date")
    suspend fun incrementProgress(date: String, minutes: Int, chapters: Int, timestamp: Long): Int

    // --- So lieu that cho the thong ke -------------------------------------
    // Truoc day the thong ke hien hang so: 48.5 gio, 158 chuong, chuoi 7 ngay,
    // 12 tac pham — bat ke nguoi dung da doc bao nhieu.

    /** Cac ngay CO doc that su, moi nhat truoc, de tinh chuoi ngay lien tiep. */
    @Query("SELECT date FROM reading_sessions WHERE minutesRead > 0 OR chaptersRead > 0 ORDER BY date DESC LIMIT :limit")
    suspend fun activeDatesOnce(limit: Int): List<String>

    @Query("SELECT COALESCE(SUM(minutesRead), 0) FROM reading_sessions")
    suspend fun totalMinutes(): Int

    @Query("SELECT COALESCE(SUM(chaptersRead), 0) FROM reading_sessions")
    suspend fun totalChapters(): Int

    @Query("SELECT COALESCE(SUM(wordsRead), 0) FROM reading_sessions")
    suspend fun totalWords(): Int

    /** Phiên đọc trong khoảng ngày — dữ liệu cho heatmap kiểu GitHub. */
    @Query("SELECT * FROM reading_sessions WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC")
    suspend fun getSessionsInRange(startDate: String, endDate: String): List<ReadingSession>
}
