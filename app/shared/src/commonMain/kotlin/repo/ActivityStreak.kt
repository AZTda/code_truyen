package com.truyencloud.shared.repo

import com.truyencloud.shared.data.ReadingSessionDao
import com.truyencloud.shared.data.ReviewLogDao
import com.truyencloud.shared.data.dateToEpochDay
import com.truyencloud.shared.data.getTodayDateString
import kotlinx.coroutines.flow.MutableStateFlow

/** Kết quả chuỗi ngày: [days] là số NGÀY CÓ HOẠT ĐỘNG trong chuỗi hiện tại, [shields] là số khiên còn lại. */
data class StreakInfo(val days: Int = 0, val shields: Int = 0)

/**
 * MỘT định nghĩa chuỗi ngày cho mọi màn hình (Sổ từ, Thống kê, Cài đặt):
 * một ngày được tính nếu người dùng đã đọc >= 1 phút/chương HOẶC ôn >= 1 thẻ (kể cả luyện tập).
 *
 * Trước đây có màn đếm ngày ĐỌC, có màn đếm ngày ÔN nên cùng một người học thấy
 * ba con số khác nhau.
 *
 * Khiên chuỗi (trước đây chỉ là `if (streak >= 3) 1 else 0` và không dùng vào đâu): cứ 7 ngày hoạt động
 * liên tiếp tặng 1 khiên (tối đa [MAX_SHIELDS]); một ngày bỏ lỡ LẺ được khiên tự động bù, chuỗi không đứt.
 * Tính bằng mô phỏng lại toàn bộ lịch sử nên không cần lưu trạng thái.
 */
object ActivityStreak {
    const val SHIELD_EVERY = 7
    const val MAX_SHIELDS = 2
    private const val HISTORY_LIMIT = 400

    // DAO đăng ký một lần khi các repository được dựng (GamificationRepository có cả hai, VocabularyRepository
    // có reviewLogDao) để bất kỳ màn hình nào cũng tính được chuỗi chung mà không cần truyền DAO qua từng lớp.
    private val registeredReading = MutableStateFlow<ReadingSessionDao?>(null)
    private val registeredReviews = MutableStateFlow<ReviewLogDao?>(null)

    fun register(reading: ReadingSessionDao? = null, reviews: ReviewLogDao? = null) {
        if (reading != null) registeredReading.value = reading
        if (reviews != null) registeredReviews.value = reviews
    }

    /** Chuỗi ngày; DAO nào truyền null thì lấy DAO đã đăng ký (xem [register]). */
    suspend fun compute(
        reading: ReadingSessionDao? = null,
        reviews: ReviewLogDao? = null,
        today: String = getTodayDateString()
    ): StreakInfo {
        val readingDates = (reading ?: registeredReading.value)?.activeDatesOnce(HISTORY_LIMIT).orEmpty()
        val reviewDates = (reviews ?: registeredReviews.value)?.activeDatesOnce(HISTORY_LIMIT).orEmpty()
        return fromDates(readingDates + reviewDates, today)
    }

    /** Hàm thuần — kiểm thử được. [activeDates] là các ngày YYYY-MM-DD có hoạt động, thứ tự bất kỳ, có thể trùng. */
    fun fromDates(activeDates: List<String>, today: String): StreakInfo {
        val todayDay = parseDay(today) ?: return StreakInfo()
        val days = activeDates.mapNotNull(::parseDay).filter { it <= todayDay }.distinct().sorted()
        if (days.isEmpty()) return StreakInfo()

        var streak = 0
        var shields = 0
        var last = days.first() - 1
        for (day in days) {
            val missed = day - last - 1
            if (missed > 0 && streak > 0) {
                if (missed == 1L && shields > 0) {
                    shields-- // khiên bù đúng một ngày bỏ lỡ
                } else {
                    streak = 0
                    shields = 0
                }
            }
            streak++
            if (streak % SHIELD_EVERY == 0) shields = minOf(MAX_SHIELDS, shields + 1)
            last = day
        }

        // Còn sống nếu hoạt động gần nhất là hôm nay / hôm qua (hôm nay chưa đọc thì chuỗi chưa đứt),
        // hoặc hôm qua bị bỏ lỡ nhưng còn khiên để bù khi người học quay lại hôm nay.
        val missedSinceLast = todayDay - last - 1
        val alive = when {
            last == todayDay -> true
            missedSinceLast <= 0L -> true
            missedSinceLast == 1L && shields > 0 -> true
            else -> false
        }
        return if (alive) StreakInfo(streak, shields) else StreakInfo()
    }

    private fun parseDay(dateKey: String): Long? {
        val p = dateKey.split("-")
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        return dateToEpochDay(y, m, d)
    }
}
