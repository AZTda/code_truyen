package com.truyencloud.shared.repo

import com.truyencloud.shared.data.InMemoryReviewLogDao
import com.truyencloud.shared.data.InMemorySavedWordDao
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.data.runNow
import com.truyencloud.shared.srs.FsrsConfig
import com.truyencloud.shared.srs.FsrsScheduler
import com.truyencloud.shared.srs.Rating
import com.truyencloud.shared.srs.ReviewPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lượt ôn phải tính trên trạng thái thẻ trong DB, không phải ảnh chụp mà nơi gọi giữ: đề Luyện tập dựng sẵn mang thẻ cũ, nên
 * chấm một thẻ ở tab Thẻ SRS rồi quay lại trả lời đề cũ sẽ lên lịch lần hai từ trạng thái TRƯỚC lượt chấm và đè mất nó.
 */
class VocabularyReviewCoordinatorTest {
    private val words = InMemorySavedWordDao()
    private val logs = InMemoryReviewLogDao()
    private val coordinator = VocabularyReviewCoordinator(words, logs, FsrsConfig(enableFuzz = false, utcOffsetMillis = 0L))
    private val day = FsrsScheduler.MILLIS_PER_DAY

    /** Thẻ đã học, đến hạn từ hôm qua và chưa ôn hôm nay — cổng ReviewGate cho phép lên lịch. */
    private fun dueCard(now: Long) = SavedWord(
        id = "word_a", word = "a", stability = 10.0, difficulty = 5.0, fsrsState = 2, reps = 5, lapses = 0,
        lastReviewEpoch = now - 10 * day, dueEpoch = now - day, createdAt = 1, updatedAt = 1, isSynced = true
    )

    @Test
    fun aStalePracticeSnapshotCannotOverwriteAnEarlierFlashcardReview() = runNow {
        val now = getCurrentEpochMillis()
        words.rows += dueCard(now)
        val stale = words.rows.single() // đề Luyện tập dựng từ trước khi thẻ được chấm

        val flashcard = coordinator.review(stale, Rating.GOOD, "recognition", 0L, ReviewPolicy.SCHEDULE)
        assertTrue(flashcard.scheduled)
        val afterFlashcard = words.rows.single()

        // Trả lời đề cũ: ảnh chụp vẫn trông "đến hạn, chưa ôn hôm nay", nhưng DB thì đã ôn rồi.
        val practice = coordinator.review(stale, Rating.AGAIN, "cloze", 0L, ReviewPolicy.SCHEDULE_IF_DUE)

        assertFalse(practice.scheduled, "Đã ôn hôm nay: lượt luyện tập chỉ ghi nhật ký")
        val now2 = words.rows.single()
        assertEquals(afterFlashcard.reps, now2.reps)
        assertEquals(afterFlashcard.stability, now2.stability)
        assertEquals(afterFlashcard.lapses, now2.lapses, "Lượt trả lời sai ở đề cũ không được tính thành một lapse")
        assertEquals(afterFlashcard.dueEpoch, now2.dueEpoch)
        assertEquals(afterFlashcard.fsrsState, now2.fsrsState)
        val log = logs.rows.last()
        assertTrue(log.isPractice)
        assertEquals(2, log.stateBefore, "Nhật ký ghi trạng thái THẬT của thẻ lúc đó")
        assertEquals(afterFlashcard.stability, practice.word.stability, "Kết quả trả về là thẻ trong DB, không phải ảnh chụp cũ")
    }

    @Test
    fun theSchedulingInputIsTheStoredStateNotTheCallersSnapshot() = runNow {
        val now = getCurrentEpochMillis()
        words.rows += dueCard(now)
        // Ảnh chụp bịa mốc cũ hơn nhiều so với DB: lượt ôn phải đi từ độ bền 10.0 của DB.
        val stale = words.rows.single().copy(stability = 1.0, reps = 1)

        val result = coordinator.review(stale, Rating.GOOD, "recognition", 0L, ReviewPolicy.SCHEDULE)

        assertEquals(10.0, result.outcome.stabilityBefore)
        assertEquals(6, words.rows.single().reps, "reps của DB (5) + 1, không phải của ảnh chụp (1) + 1")
    }

    @Test
    fun reviewingAWordThatIsGoneChangesNothingAndLogsNothing() = runNow {
        val now = getCurrentEpochMillis()
        val ghost = dueCard(now) // không có trong DB (đã xoá)

        val result = coordinator.review(ghost, Rating.GOOD, "recognition", 0L, ReviewPolicy.SCHEDULE)

        assertFalse(result.scheduled)
        assertTrue(words.rows.isEmpty())
        assertTrue(logs.rows.isEmpty())
    }

    @Test
    fun practiceOnlyStillReturnsTheStoredWordUntouched() = runNow {
        val now = getCurrentEpochMillis()
        words.rows += dueCard(now)
        val before = words.rows.single()

        val result = coordinator.review(before.copy(stability = 99.0), Rating.GOOD, "cram", 0L, ReviewPolicy.PRACTICE_ONLY)

        assertFalse(result.scheduled)
        assertEquals(before, words.rows.single())
        assertEquals(before, result.word)
        assertTrue(logs.rows.single().isPractice)
    }

    @Test
    fun undoReviewRestoresPreviousMemoryStateAndRemovesReviewLog() = runNow {
        val now = getCurrentEpochMillis()
        words.rows += dueCard(now)
        val before = words.rows.single()

        val result = coordinator.review(before, Rating.AGAIN, "recognition", 0L, ReviewPolicy.SCHEDULE)
        assertTrue(result.scheduled)
        assertEquals(1, logs.rows.size)
        assertEquals(1, words.rows.single().lapses)

        coordinator.undoReview(result.previousWord, result.logId)

        val restored = words.rows.single()
        assertEquals(before.stability, restored.stability)
        assertEquals(before.reps, restored.reps)
        assertEquals(before.lapses, restored.lapses)
        assertEquals(before.dueEpoch, restored.dueEpoch)
        assertTrue(logs.rows.isEmpty(), "Dòng nhật ký vừa ghi phải được xoá khi Hoàn tác")
    }
}
