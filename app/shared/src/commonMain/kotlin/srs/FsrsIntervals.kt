package com.truyencloud.shared.srs

import kotlin.math.roundToLong

/**
 * Khoảng cách ôn (ngày) của FSRS: làm tròn, kẹp trần, xáo nhẹ, và ÉP thứ tự giữa ba nút "đạt".
 *
 * Mỗi nút được tính độc lập từ độ bền mới của nó, nên khi thẻ vừa học lại (đã ôn cách đây chỉ vài phút, độ bền gần như
 * không tăng) cả Khó / Tốt / Dễ có thể ra cùng một số ngày — "1 ngày / 1 ngày / 1 ngày" — và nút Dễ vô nghĩa. Bản
 * tham chiếu (fsrs4anki, py-fsrs) ép hard = min(hard, good), good = max(good, hard + 1), easy = max(easy, good + 1).
 *
 * Cả [FsrsScheduler.review] lẫn [FsrsScheduler.previewIntervals] đi qua đây (xem trước chỉ gọi lại review cho từng nút)
 * nên nhãn trên nút luôn trùng với kết quả thật.
 */
internal object FsrsIntervals {

    /** Phút của bước học ngắn cho [rating], hoặc null nếu nút này lên lịch theo ngày. */
    fun shortTermMinutes(rating: Rating, isNew: Boolean, config: FsrsConfig): Long? {
        if (!config.enableShortTermSteps) return null
        return when {
            rating == Rating.AGAIN -> 5L
            isNew && rating == Rating.HARD -> 10L
            else -> null
        }
    }

    /** Số ngày ứng với một độ bền: làm tròn, kẹp [1, trần], rồi xáo nhẹ theo [fuzzKey] (null = không xáo). */
    fun days(stability: Double, config: FsrsConfig, fuzzKey: String?, salt: Int): Long {
        val raw = FsrsScheduler.intervalForRetention(stability, config.desiredRetention)
        val days = raw.roundToLong().coerceIn(1L, config.maximumIntervalDays)
        return if (fuzzKey != null && config.enableFuzz) {
            FsrsFuzz.apply(days, fuzzKey, salt, config.maximumIntervalDays)
        } else days
    }

    /**
     * Khoảng cách (ngày) của [rating] ∈ {Khó, Tốt, Dễ} SAU khi ép thứ tự Khó < Tốt < Dễ, mỗi bậc cách nhau >= 1 ngày.
     * Xáo nhẹ chạy TRƯỚC khi ép thứ tự để kết quả cuối cùng luôn đúng thứ tự dù xáo thế nào.
     * Nút Khó của thẻ mới là bước học ngắn (không có số ngày) nên không tham gia thứ tự — chỉ còn Tốt < Dễ.
     *
     * @param stabilityFor độ bền mới ứng với từng nút.
     */
    fun orderedDays(
        rating: Rating,
        isNew: Boolean,
        config: FsrsConfig,
        fuzzKey: String?,
        salt: Int,
        stabilityFor: (Rating) -> Double
    ): Long {
        fun daysOf(r: Rating) = days(stabilityFor(r), config, fuzzKey, salt)
        val hard = if (shortTermMinutes(Rating.HARD, isNew, config) == null) daysOf(Rating.HARD) else null
        val ordered = order(hard, daysOf(Rating.GOOD), daysOf(Rating.EASY), config.maximumIntervalDays)
        return when (rating) {
            Rating.AGAIN -> daysOf(Rating.AGAIN) // Quên không tham gia thứ tự (thường là bước học ngắn)
            Rating.HARD -> ordered.hard ?: daysOf(Rating.HARD)
            Rating.GOOD -> ordered.good
            Rating.EASY -> ordered.easy
        }
    }

    class Ordered(val hard: Long?, val good: Long, val easy: Long)

    /** Ép thứ tự theo bản tham chiếu; không vượt [maxDays] (ở trần thì các nút buộc phải bằng nhau). */
    fun order(hard: Long?, good: Long, easy: Long, maxDays: Long): Ordered {
        val h = hard?.let { minOf(it, good) }
        val g = (if (h != null) maxOf(good, h + 1) else good).coerceAtMost(maxDays)
        val e = maxOf(easy, g + 1).coerceAtMost(maxDays)
        return Ordered(h, g, e)
    }
}
