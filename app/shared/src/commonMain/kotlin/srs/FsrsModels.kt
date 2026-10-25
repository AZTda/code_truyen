package com.truyencloud.shared.srs

/**
 * Các kiểu dữ liệu và mô hình trạng thái cho thuật toán FSRS-4.5.
 */

const val LEECH_THRESHOLD = 8
const val DEFAULT_MAX_INTERVAL_DAYS: Long = 365L * 3L

val DEFAULT_FSRS_WEIGHTS: DoubleArray = doubleArrayOf(
    0.4872, 1.4003, 3.7145, 13.8206,
    5.1618, 1.2298, 0.8975, 0.0310,
    1.6474, 0.1367, 1.0461, 2.1072,
    0.0793, 0.3246, 1.5870, 0.2272,
    2.8755
)

/** Điểm người học tự chấm sau khi lật thẻ. */
enum class Rating(val value: Int) {
    AGAIN(1), HARD(2), GOOD(3), EASY(4);

    companion object {
        fun from(value: Int): Rating = entries.firstOrNull { it.value == value } ?: GOOD
    }
}

/** Trạng thái vòng đời của một thẻ. */
enum class CardState(val value: Int) {
    NEW(0), LEARNING(1), REVIEW(2), RELEARNING(3);

    companion object {
        fun from(value: Int): CardState = entries.firstOrNull { it.value == value } ?: NEW
    }
}

/** Ảnh chụp trạng thái trí nhớ của một thẻ tại một thời điểm. */
data class MemoryState(
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val state: CardState = CardState.NEW,
    val reps: Int = 0,
    val lapses: Int = 0,
    val lastReviewEpoch: Long = 0L,
    val dueEpoch: Long = 0L
) {
    val isLeech: Boolean get() = lapses >= LEECH_THRESHOLD
}

/** Kết quả sau một lần ôn: trạng thái mới + mọi số liệu cần ghi vào nhật ký. */
data class ReviewOutcome(
    val state: MemoryState,
    val intervalDays: Long,
    val elapsedDays: Double,
    val retrievabilityBefore: Double,
    val stabilityBefore: Double,
    val difficultyBefore: Double
)

/** Cấu hình do người học chỉnh trong phần Cài đặt. */
data class FsrsConfig(
    val desiredRetention: Double = 0.90,
    val maximumIntervalDays: Long = DEFAULT_MAX_INTERVAL_DAYS,
    val weights: DoubleArray = DEFAULT_FSRS_WEIGHTS,
    val enableShortTermSteps: Boolean = true,
    /** Xáo nhẹ khoảng cách >= 3 ngày để các thẻ học cùng ngày không dồn về cùng một ngày ôn. */
    val enableFuzz: Boolean = true,
    /**
     * Độ lệch múi giờ (ms) dùng để làm tròn hạn ôn về đầu ngày học.
     * null = lấy múi giờ của thiết bị. Kiểm thử truyền giá trị cố định để kết quả ổn định.
     */
    val utcOffsetMillis: Long? = null,
    /** Giờ bắt đầu một "ngày học" theo giờ địa phương (giống Anki: 4 giờ sáng). */
    val dayStartHour: Int = StudyDay.DEFAULT_DAY_START_HOUR
) {
    override fun equals(other: Any?): Boolean =
        other is FsrsConfig &&
            desiredRetention == other.desiredRetention &&
            maximumIntervalDays == other.maximumIntervalDays &&
            enableShortTermSteps == other.enableShortTermSteps &&
            enableFuzz == other.enableFuzz &&
            utcOffsetMillis == other.utcOffsetMillis &&
            dayStartHour == other.dayStartHour &&
            weights.contentEquals(other.weights)

    override fun hashCode(): Int =
        desiredRetention.hashCode() * 31 +
            maximumIntervalDays.hashCode() * 31 +
            enableShortTermSteps.hashCode() * 31 +
            enableFuzz.hashCode() * 31 +
            utcOffsetMillis.hashCode() * 31 +
            dayStartHour * 31 +
            weights.contentHashCode()
}
