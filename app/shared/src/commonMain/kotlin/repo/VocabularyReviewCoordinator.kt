package com.truyencloud.shared.repo

import com.truyencloud.shared.data.ReviewLog
import com.truyencloud.shared.data.ReviewLogDao
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.data.getTodayDateString
import com.truyencloud.shared.srs.FsrsConfig
import com.truyencloud.shared.srs.FsrsScheduler
import com.truyencloud.shared.srs.MemoryState
import com.truyencloud.shared.srs.Rating
import com.truyencloud.shared.srs.ReviewGate
import com.truyencloud.shared.srs.ReviewOutcome
import com.truyencloud.shared.srs.ReviewPolicy
import com.truyencloud.shared.srs.StudyDay

/** Kết quả một lượt trả lời: [scheduled] = false nghĩa là chỉ ghi nhật ký luyện tập, lịch FSRS KHÔNG đổi. */
data class ReviewResult(
    val outcome: ReviewOutcome,
    val scheduled: Boolean,
    /** Thẻ sau lượt ôn — đã áp trạng thái FSRS mới nếu [scheduled]. */
    val word: SavedWord,
    /** Bản chụp thẻ trước lượt ôn — phục vụ nút Hoàn tác (Undo). */
    val previousWord: SavedWord = word,
    /** Khoá dòng nhật ký vừa ghi — xoá khi Hoàn tác để không lệch thống kê ngày. */
    val logId: Long = 0L
)

/**
 * Ghi một lượt ôn: quyết định có đổi lịch FSRS không (xem [ReviewPolicy]), ghi thẻ và ghi nhật ký.
 */
class VocabularyReviewCoordinator(
    private val savedWordDao: SavedWordDao,
    private val reviewLogDao: ReviewLogDao?,
    private val fsrsConfig: FsrsConfig
) {
    suspend fun review(
        word: SavedWord,
        rating: Rating,
        reviewMode: String,
        responseMs: Long,
        policy: ReviewPolicy
    ): ReviewResult {
        val now = getCurrentEpochMillis()
        val startOfDay = StudyDay.startOfToday(now)
        // Luôn đọc lại thẻ từ DB: [word] có thể là ảnh chụp cũ (đề Luyện tập dựng trước khi thẻ được chấm ở tab Thẻ SRS).
        // Cổng ReviewGate và đầu vào FSRS mà tính trên ảnh cũ sẽ lên lịch lần hai từ trạng thái trước lượt ôn và đè mất kết quả thật.
        val fresh = savedWordDao.getById(word.id)
            ?: return ReviewResult(unchanged(word.toMemoryState(), now), scheduled = false, word = word) // thẻ đã bị xoá
        val current = repairClampedDifficulty(fresh)
        val scheduled = ReviewGate.shouldSchedule(current, policy, now, startOfDay)

        val outcome: ReviewOutcome
        val after: SavedWord
        if (scheduled) {
            outcome = FsrsScheduler.review(current, rating, now, fsrsConfig, fuzzKey = fresh.id)
            savedWordDao.applyFsrsResult(
                id = fresh.id,
                stability = outcome.state.stability,
                difficulty = outcome.state.difficulty,
                fsrsState = outcome.state.state.value,
                reps = outcome.state.reps,
                lapses = outcome.state.lapses,
                lastReviewEpoch = now,
                dueEpoch = outcome.state.dueEpoch,
                masteredCount = if (outcome.state.stability >= 21.0) 1 else 0,
                legacyStage = legacyStageFor(outcome.state),
                updatedAt = now
            )
            after = fresh.withMemory(outcome.state, now)
        } else {
            outcome = unchanged(current, now)
            after = fresh
        }
        val logId = writeLog(fresh, rating, reviewMode, responseMs, outcome, isPractice = !scheduled, now = now)
        return ReviewResult(outcome, scheduled, after, previousWord = fresh, logId = logId)
    }

    /** Hoàn tác lượt chấm vừa rồi: trả thẻ về trạng thái trước đó và xoá dòng nhật ký vừa ghi. */
    suspend fun undoReview(previousWord: SavedWord, logId: Long) {
        val now = getCurrentEpochMillis()
        savedWordDao.upsertWord(previousWord.copy(updatedAt = now, isSynced = false))
        if (logId > 0L) {
            reviewLogDao?.deleteById(logId)
        }
    }

    /**
     * Thẻ bị công thức FSRS lai cũ kẹp độ khó về đúng 1.0 thì tính lại từ lịch sử điểm trước khi ôn —
     * nếu không, lượt ôn này vẫn xuất phát từ D = 1 và cho khoảng cách quá dài.
     */
    private suspend fun repairClampedDifficulty(word: SavedWord): MemoryState {
        val state = word.toMemoryState()
        if (!isClamped(word)) return state
        return state.copy(difficulty = replayedDifficulty(word))
    }

    /**
     * Độ khó phát lại từ MỌI log hiện có; không có log nào thì lùi về D0(Tốt). Chỉ để TÍNH lượt ôn này, không ghi vào thẻ —
     * ghi đè dữ liệu bền vững phải dùng [replayedDifficultyOrNull].
     */
    suspend fun replayedDifficulty(word: SavedWord): Double =
        FsrsScheduler.replayDifficulty(loggedRatings(word), fsrsConfig.weights)
            ?: FsrsScheduler.initialDifficulty(Rating.GOOD, fsrsConfig.weights)

    /**
     * Độ khó phát lại từ lịch sử điểm, hoặc null nếu KHÔNG đủ lịch sử tin cậy: chưa có log nào, hoặc ít log hơn số lượt
     * [SavedWord.reps] (nhật ký bị mất / chưa kéo về, thẻ di trú). Bảo trì dữ liệu dùng hàm này để bỏ qua các thẻ đó
     * thay vì ghi đè độ khó thật bằng giá trị mặc định hay bằng bản phát lại cụt đầu.
     */
    suspend fun replayedDifficultyOrNull(word: SavedWord): Double? {
        val ratings = loggedRatings(word)
        if (ratings.isEmpty() || ratings.size < word.reps) return null
        return FsrsScheduler.replayDifficulty(ratings, fsrsConfig.weights)
    }

    private suspend fun loggedRatings(word: SavedWord): List<Rating> =
        reviewLogDao?.ratingsForWord(word.id).orEmpty().map { Rating.from(it) }

    private fun isClamped(word: SavedWord) = word.reps > 0 && word.difficulty > 0.0 && word.difficulty <= 1.0

    private fun unchanged(state: MemoryState, now: Long): ReviewOutcome {
        val elapsed = FsrsScheduler.elapsedDays(state, now)
        return ReviewOutcome(
            state = state,
            intervalDays = 0L,
            elapsedDays = elapsed,
            retrievabilityBefore = FsrsScheduler.retrievability(elapsed, state.stability),
            stabilityBefore = state.stability,
            difficultyBefore = state.difficulty
        )
    }

    private suspend fun writeLog(
        word: SavedWord,
        rating: Rating,
        reviewMode: String,
        responseMs: Long,
        outcome: ReviewOutcome,
        isPractice: Boolean,
        now: Long
    ): Long {
        val dao = reviewLogDao ?: return 0L
        // (wordId, reviewedAt) là khoá tự nhiên của log: hai lượt chạm cùng mili-giây thì đẩy lượt sau lên 1ms.
        var at = now
        var guard = 0
        while (dao.countByKey(word.id, at) > 0 && guard++ < 50) at++
        return dao.insert(
            ReviewLog(
                wordId = word.id,
                word = word.word,
                reviewedAt = at,
                rating = rating.value,
                reviewMode = reviewMode,
                responseMs = responseMs,
                elapsedDays = outcome.elapsedDays,
                scheduledDays = outcome.intervalDays.toDouble(),
                stabilityBefore = outcome.stabilityBefore,
                stabilityAfter = outcome.state.stability,
                difficultyBefore = outcome.difficultyBefore,
                difficultyAfter = outcome.state.difficulty,
                retrievabilityBefore = outcome.retrievabilityBefore,
                dateKey = getTodayDateString(),
                isPractice = isPractice,
                stateBefore = word.fsrsState,
                isSynced = false
            )
        )
    }
}
