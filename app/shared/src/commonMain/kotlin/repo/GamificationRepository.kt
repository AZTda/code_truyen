package com.truyencloud.shared.repo

import com.truyencloud.shared.data.ReadingSessionDao
import com.truyencloud.shared.data.ReviewLogDao
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Nguồn "Từ hôm nay" của Tủ sách. Cấp độ / XP / nhiệm vụ ngày đã bỏ hẳn khỏi ứng dụng; tên lớp giữ lại vì
 * AppNav dựng nó và nơi đăng ký DAO cho chuỗi ngày dùng chung ([ActivityStreak]) vẫn là ở đây.
 */
class GamificationRepository(
    readingSessionDao: ReadingSessionDao?,
    reviewLogDao: ReviewLogDao?,
    private val savedWordDao: SavedWordDao
) {
    init {
        // Một định nghĩa chuỗi ngày cho mọi màn hình (xem ActivityStreak): Sổ từ và Thống kê tính chuỗi cả ngày đọc
        // lẫn ngày ôn nhờ DAO đọc truyện được đăng ký ở đây.
        ActivityStreak.register(reading = readingSessionDao, reviews = reviewLogDao)
    }

    suspend fun getWordOfTheDay(): SavedWord? =
        WordOfTheDay.pick(savedWordDao.getAllWordsOnce(), getCurrentEpochMillis())

    /**
     * Theo dõi kho từ vựng: xoá dữ liệu trên máy, đổi tài khoản, đồng bộ từ về hay lưu thêm từ thì thẻ "Từ vựng hôm nay"
     * đổi theo ngay (kho trống thì phát null), không giữ từ cũ cho tới khi tắt app.
     */
    fun observeWordOfTheDay(): Flow<SavedWord?> =
        savedWordDao.getAllWords()
            .map { WordOfTheDay.pick(it, getCurrentEpochMillis()) }
            .distinctUntilChanged()
}
