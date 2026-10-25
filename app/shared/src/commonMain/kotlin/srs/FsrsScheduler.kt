package com.truyencloud.shared.srs

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * FSRS (Free Spaced Repetition Scheduler) v4.5 — thuần Kotlin, chạy được trên mọi target KMP.
 *
 * Vì sao thay thuật toán cũ:
 * Bản cũ gán cứng 1/2/4/10 ngày theo nút bấm. Khoảng cách KHÔNG BAO GIỜ tăng, nên một
 * người học 500 từ sẽ phải ôn ~50 thẻ mỗi ngày mãi mãi — không từ nào "tốt nghiệp".
 * FSRS mô hình hóa trí nhớ bằng 3 đại lượng và cho khoảng cách giãn dần theo đúng
 * mức độ đã thuộc của từng từ:
 *
 *  - Stability (S): số ngày để xác suất nhớ lại rơi từ 100% xuống đúng 90%.
 *  - Difficulty (D): độ khó nội tại của thẻ, thang 1..10.
 *  - Retrievability (R): xác suất nhớ lại tại thời điểm hiện tại.
 *
 * Tham chiếu công thức: open-spaced-repetition/fsrs4anki (FSRS v4.5).
 * LƯU Ý: bộ trọng số là của 4.5 nên công thức độ khó cũng PHẢI là của 4.5
 * (D0 tuyến tính theo điểm). Bản trước trộn công thức D0 của FSRS-5 vào trọng số 4.5,
 * làm mọi thẻ chấm Tốt/Dễ bị kẹp về D = 1 và khoảng cách dài gần gấp đôi.
 */
object FsrsScheduler {

    /** Bộ trọng số mặc định của FSRS-4.5 (huấn luyện trên tập dữ liệu Anki cộng đồng). */
    val DEFAULT_WEIGHTS: DoubleArray = DEFAULT_FSRS_WEIGHTS

    private const val DECAY = -0.5
    /** FACTOR = 0.9^(1/DECAY) - 1 */
    private const val FACTOR = 19.0 / 81.0

    const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L

    /** Khoảng cách tối đa: 3 năm. Xa hơn nữa thì lịch ôn mất ý nghĩa thực tế. */
    const val DEFAULT_MAX_INTERVAL_DAYS: Long = 365L * 3L

    /** Số lần quên liên tiếp để đánh dấu một từ là "leech" (học mãi không vào). */
    const val LEECH_THRESHOLD = 8

    typealias Rating = com.truyencloud.shared.srs.Rating
    typealias CardState = com.truyencloud.shared.srs.CardState
    typealias MemoryState = com.truyencloud.shared.srs.MemoryState
    typealias ReviewOutcome = com.truyencloud.shared.srs.ReviewOutcome
    typealias Config = com.truyencloud.shared.srs.FsrsConfig

    // -----------------------------------------------------------------------
    // Công thức lõi FSRS-4.5
    // -----------------------------------------------------------------------

    /** Xác suất nhớ lại sau `elapsedDays` ngày với độ bền `stability`. */
    fun retrievability(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 0.0
        val t = max(0.0, elapsedDays)
        return (1.0 + FACTOR * t / stability).pow(DECAY)
    }

    /** Số ngày cần chờ để xác suất nhớ rơi đúng xuống `desiredRetention`. */
    fun intervalForRetention(stability: Double, desiredRetention: Double): Double {
        if (stability <= 0.0) return 0.0
        val r = desiredRetention.coerceIn(0.70, 0.99)
        return stability / FACTOR * (r.pow(1.0 / DECAY) - 1.0)
    }

    /** S0(G) = w[G-1]. */
    fun initialStability(rating: Rating, w: DoubleArray = DEFAULT_WEIGHTS): Double =
        max(0.1, w[rating.value - 1])

    /** FSRS-4.5: D0(G) = w4 − (G − 3)·w5, kẹp trong [1, 10]. */
    fun initialDifficulty(rating: Rating, w: DoubleArray = DEFAULT_WEIGHTS): Double =
        clampDifficulty(w[4] - (rating.value - 3) * w[5])

    /** FSRS-4.5: D' = w7·D0(3) + (1 − w7)·(D − w6·(G − 3)), kẹp trong [1, 10]. */
    fun nextDifficulty(difficulty: Double, rating: Rating, w: DoubleArray = DEFAULT_WEIGHTS): Double {
        val delta = difficulty - w[6] * (rating.value - 3)
        // Hồi quy về D0(Tốt) = w4: tránh một chuỗi trả lời xấu đẩy thẻ vào trạng thái khó vĩnh viễn.
        return clampDifficulty(w[7] * initialDifficulty(Rating.GOOD, w) + (1.0 - w[7]) * delta)
    }

    /**
     * Tính lại độ khó từ lịch sử điểm (theo thứ tự thời gian).
     * Dùng một lần để sửa các thẻ đã bị công thức lai cũ kẹp sai độ khó.
     */
    fun replayDifficulty(ratings: List<Rating>, w: DoubleArray = DEFAULT_WEIGHTS): Double? {
        if (ratings.isEmpty()) return null
        var d = initialDifficulty(ratings.first(), w)
        for (r in ratings.drop(1)) d = nextDifficulty(d, r, w)
        return d
    }

    private fun clampDifficulty(d: Double): Double = d.coerceIn(1.0, 10.0)

    private fun stabilityAfterRecall(
        w: DoubleArray,
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: Rating
    ): Double {
        val hardPenalty = if (rating == Rating.HARD) w[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) w[16] else 1.0
        val growth = exp(w[8]) *
            (11.0 - difficulty) *
            stability.pow(-w[9]) *
            (exp(w[10] * (1.0 - retrievability)) - 1.0) *
            hardPenalty *
            easyBonus
        return max(0.1, stability * (1.0 + growth))
    }

    private fun stabilityAfterForget(
        w: DoubleArray,
        difficulty: Double,
        stability: Double,
        retrievability: Double
    ): Double {
        val s = w[11] *
            difficulty.pow(-w[12]) *
            ((stability + 1.0).pow(w[13]) - 1.0) *
            exp(w[14] * (1.0 - retrievability))
        // Không bao giờ cho độ bền tăng lên sau khi quên
        return max(0.1, min(s, stability))
    }

    // -----------------------------------------------------------------------
    // API chính
    // -----------------------------------------------------------------------

    /**
     * Tính trạng thái mới của một thẻ sau khi người học chấm điểm.
     *
     * @param current trạng thái hiện tại (dùng [MemoryState] rỗng cho thẻ mới)
     * @param rating điểm người học vừa chấm
     * @param nowEpoch thời điểm ôn (epoch millis)
     * @param fuzzKey khoá tất định để xáo khoảng cách (thường là id thẻ); null = không xáo
     */
    fun review(
        current: MemoryState,
        rating: Rating,
        nowEpoch: Long,
        config: Config = Config(),
        fuzzKey: String? = null
    ): ReviewOutcome {
        val w = config.weights
        val isNew = current.state == CardState.NEW || current.stability <= 0.0

        val elapsedDays: Double = if (isNew || current.lastReviewEpoch <= 0L) {
            0.0
        } else {
            max(0.0, (nowEpoch - current.lastReviewEpoch).toDouble() / MILLIS_PER_DAY)
        }

        val rBefore = if (isNew) 0.0 else retrievability(elapsedDays, current.stability)
        // Độ khó không hợp lệ (dữ liệu đồng bộ thiếu trường) thì coi như mức trung bình.
        val baseDifficulty = if (current.difficulty < 1.0) initialDifficulty(Rating.GOOD, w) else current.difficulty

        // Độ bền mới nếu người học bấm [r] — cần cho CẢ ba nút Khó/Tốt/Dễ để ép thứ tự khoảng cách (xem FsrsIntervals).
        fun stabilityFor(r: Rating): Double = when {
            isNew -> initialStability(r, w)
            r == Rating.AGAIN -> stabilityAfterForget(w, baseDifficulty, current.stability, rBefore)
            else -> {
                val recalled = stabilityAfterRecall(w, baseDifficulty, current.stability, rBefore, r)
                if (current.state == CardState.LEARNING && current.lapses == 0 && r >= Rating.GOOD && elapsedDays < 1.0) {
                    maxOf(recalled, initialStability(r, w))
                } else recalled
            }
        }
        val newStability = stabilityFor(rating)
        val newDifficulty = if (isNew) initialDifficulty(rating, w) else nextDifficulty(baseDifficulty, rating, w)

        // Chỉ tính là "quên" khi một thẻ ĐÃ tốt nghiệp (Review) bị chấm Quên —
        // thẻ mới hay thẻ đang trong bước học lại chưa có gì để quên.
        val isLapse = !isNew && current.state == CardState.REVIEW && rating == Rating.AGAIN
        val newLapses = current.lapses + if (isLapse) 1 else 0

        // Thẻ mới / vừa quên: ôn lại trong cùng phiên thay vì đợi sang hôm sau.
        val shortTermMinutes = FsrsIntervals.shortTermMinutes(rating, isNew, config)

        val newState = when {
            shortTermMinutes == null -> CardState.REVIEW
            !isNew && (current.state == CardState.REVIEW || current.state == CardState.RELEARNING) ->
                CardState.RELEARNING
            else -> CardState.LEARNING
        }

        val intervalDays: Long
        val dueEpoch: Long
        if (shortTermMinutes != null) {
            intervalDays = 0L
            dueEpoch = nowEpoch + shortTermMinutes * 60L * 1000L
        } else {
            intervalDays = if (rating == Rating.AGAIN) {
                FsrsIntervals.days(newStability, config, fuzzKey, current.reps)
            } else {
                FsrsIntervals.orderedDays(rating, isNew, config, fuzzKey, current.reps, ::stabilityFor)
            }
            val offset = config.utcOffsetMillis ?: platformUtcOffsetMillis(nowEpoch)
            dueEpoch = StudyDay.dueForInterval(nowEpoch, intervalDays, offset, config.dayStartHour)
        }

        return ReviewOutcome(
            state = MemoryState(
                stability = newStability,
                difficulty = newDifficulty,
                state = newState,
                reps = current.reps + 1,
                lapses = newLapses,
                lastReviewEpoch = nowEpoch,
                dueEpoch = dueEpoch
            ),
            intervalDays = intervalDays,
            elapsedDays = elapsedDays,
            retrievabilityBefore = rBefore,
            stabilityBefore = current.stability,
            difficultyBefore = current.difficulty
        )
    }

    /**
     * Xem trước khoảng cách ứng với từng nút — để hiển thị "Quên · 5 phút / Tốt · 12 ngày"
     * thay cho nhãn cố định "1 ngày / 2 ngày / 4 ngày / 10 ngày" của bản cũ.
     */
    fun previewIntervals(
        current: MemoryState,
        nowEpoch: Long,
        config: Config = Config(),
        fuzzKey: String? = null
    ): Map<Rating, ReviewOutcome> =
        Rating.entries.associateWith { review(current, it, nowEpoch, config, fuzzKey) }

    /** Nhãn tiếng Việt ngắn gọn cho một khoảng cách. */
    fun formatInterval(outcome: ReviewOutcome, nowEpoch: Long): String {
        if (outcome.intervalDays <= 0L) {
            val minutes = max(1L, (outcome.state.dueEpoch - nowEpoch) / 60_000L)
            return "$minutes phút"
        }
        val d = outcome.intervalDays
        return when {
            d < 30L -> "$d ngày"
            d < 365L -> {
                val months = (d / 30.0)
                if (months < 10) "${((months * 10).roundToLong() / 10.0)} tháng" else "${months.roundToLong()} tháng"
            }
            else -> {
                val years = d / 365.0
                "${((years * 10).roundToLong() / 10.0)} năm"
            }
        }
    }

    /**
     * Điểm ưu tiên giữa các thẻ ĐÃ đến hạn: càng gần ngưỡng quên (R thấp) càng gấp,
     * cộng thêm cho từ thuộc cuốn truyện đang đọc dở. Thẻ mới KHÔNG còn được cộng điểm
     * ở đây — chúng đi hàng riêng theo hạn mức thẻ mới mỗi ngày (xem StudyQueueBuilder).
     */
    fun queuePriority(
        state: MemoryState,
        nowEpoch: Long,
        bookTitle: String = "",
        activeBookTitle: String? = null
    ): Double {
        val r = if (state.stability <= 0.0) 0.0 else retrievability(elapsedDays(state, nowEpoch), state.stability)
        val urgency = 1.0 - r
        // Thẻ đang ở bước học ngắn (vừa bấm Quên/Khó trong ngày) đến hạn thì học lại ngay cho nóng.
        val stepBoost = if (state.state == CardState.LEARNING || state.state == CardState.RELEARNING) 1.0 else 0.0
        val contextBoost = when {
            !activeBookTitle.isNullOrBlank() && bookTitle.equals(activeBookTitle, ignoreCase = true) -> 0.45
            bookTitle.isNotBlank() -> 0.12
            else -> 0.0
        }
        // Thẻ leech xuống cuối hàng: cần đổi cách học chứ không phải lặp lại thêm
        val leechPenalty = if (state.isLeech) -0.5 else 0.0
        return urgency + stepBoost + contextBoost + leechPenalty
    }

    /** Số ngày đã trôi qua kể từ lần ôn gần nhất. */
    fun elapsedDays(state: MemoryState, nowEpoch: Long): Double =
        if (state.lastReviewEpoch <= 0L) 0.0
        else max(0.0, (nowEpoch - state.lastReviewEpoch).toDouble() / MILLIS_PER_DAY)

    /**
     * Ước lượng độ bền ban đầu khi nâng cấp dữ liệu cũ (chỉ có `srsStage` 0..4)
     * sang FSRS, để người dùng cũ không bị reset toàn bộ tiến độ.
     */
    fun migrateFromLegacyStage(legacyStage: Int, nextReviewEpoch: Long, nowEpoch: Long): MemoryState {
        if (legacyStage <= 0) return MemoryState()
        // Khoảng cách của bản cũ theo stage: 1, 2, 4, 10 ngày
        val legacyIntervalDays = when (legacyStage) {
            1 -> 1.0
            2 -> 2.0
            3 -> 4.0
            else -> 10.0
        }
        // Đảo ngược công thức khoảng cách ở mức giữ nhớ 0.9 để suy ra stability tương đương
        val stability = max(0.5, legacyIntervalDays * FACTOR / (0.9.pow(1.0 / DECAY) - 1.0))
        val lastReview = if (nextReviewEpoch > 0L) {
            nextReviewEpoch - (legacyIntervalDays * MILLIS_PER_DAY).toLong()
        } else nowEpoch
        return MemoryState(
            stability = stability,
            difficulty = 5.0,
            state = CardState.REVIEW,
            reps = legacyStage,
            lapses = 0,
            lastReviewEpoch = lastReview,
            dueEpoch = if (nextReviewEpoch > 0L) nextReviewEpoch else nowEpoch
        )
    }
}
