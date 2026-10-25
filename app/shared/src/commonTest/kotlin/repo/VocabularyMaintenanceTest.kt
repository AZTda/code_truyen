package com.truyencloud.shared.repo

import com.truyencloud.shared.data.InMemoryReviewLogDao
import com.truyencloud.shared.data.InMemorySavedWordDao
import com.truyencloud.shared.data.ReviewLog
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.runNow
import com.truyencloud.shared.srs.FsrsConfig
import com.truyencloud.shared.srs.FsrsScheduler
import com.truyencloud.shared.srs.Rating
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sửa độ khó bị kẹp về 1.0 chỉ khi phát lại được ĐỦ lịch sử. Bản cũ gán D0(Tốt) = 5.16 cho thẻ thiếu log (kể cả thẻ có D = 1.0
 * hợp lệ nhờ chuỗi toàn "Dễ") rồi đẩy lên với updatedAt = bây giờ, thắng last-write-wins ở mọi máy.
 */
class VocabularyMaintenanceTest {
    private val words = InMemorySavedWordDao()
    private val logs = InMemoryReviewLogDao()
    private val review = VocabularyReviewCoordinator(words, logs, FsrsConfig())
    private val maintenance = VocabularyMaintenance(words, logs, review)

    private fun clamped(id: String, reps: Int) = SavedWord(
        id = id, word = id.removePrefix("word_"), difficulty = 1.0, stability = 30.0, fsrsState = 2, reps = reps,
        updatedAt = 123, createdAt = 1, isSynced = true
    )

    private fun history(wordId: String, vararg ratings: Rating, practice: Boolean = false) {
        ratings.forEachIndexed { i, r ->
            logs.rows += ReviewLog(id = logs.rows.size + 1L, wordId = wordId, reviewedAt = 1_000L + i, rating = r.value, isPractice = practice)
        }
    }

    @Test
    fun anAllEasyHistoryKeepsItsLegitimateMinimumDifficulty() = runNow {
        words.rows += clamped("word_easy", reps = 6)
        history("word_easy", *Array(6) { Rating.EASY })

        assertEquals(0, maintenance.repairClampedDifficulties())

        val w = words.rows.single()
        assertEquals(1.0, w.difficulty)
        assertEquals(123L, w.updatedAt)
        assertTrue(w.isSynced)
    }

    @Test
    fun aWordWithoutAnyLogsIsSkippedNotResetToTheDefault() = runNow {
        words.rows += clamped("word_nologs", reps = 6)

        assertEquals(0, maintenance.repairClampedDifficulties())

        val w = words.rows.single()
        assertEquals(1.0, w.difficulty, "Không đủ lịch sử thì để nguyên, không thay bằng 5.16")
        assertEquals(123L, w.updatedAt)
        assertTrue(w.isSynced, "Không đánh dấu để đẩy lên")
    }

    @Test
    fun aPartialHistoryIsSkipped() = runNow {
        words.rows += clamped("word_tail", reps = 6)
        history("word_tail", Rating.GOOD, Rating.GOOD) // chỉ còn 2 log trong số 6 lượt (log cũ chưa kéo về)

        assertEquals(0, maintenance.repairClampedDifficulties())
        assertEquals(1.0, words.rows.single().difficulty)
    }

    @Test
    fun practiceLogsDoNotCountAsHistory() = runNow {
        words.rows += clamped("word_p", reps = 3)
        history("word_p", Rating.GOOD, Rating.GOOD, Rating.GOOD, practice = true)

        assertEquals(0, maintenance.repairClampedDifficulties())
        assertEquals(1.0, words.rows.single().difficulty)
    }

    @Test
    fun aCompleteHistoryThatDisagreesIsRepairedWithoutTouchingTheSyncFlags() = runNow {
        words.rows += clamped("word_ok", reps = 3)
        history("word_ok", Rating.GOOD, Rating.GOOD, Rating.GOOD)

        assertEquals(1, maintenance.repairClampedDifficulties())

        val w = words.rows.single()
        val expected = FsrsScheduler.replayDifficulty(listOf(Rating.GOOD, Rating.GOOD, Rating.GOOD))
        assertNotNull(expected)
        assertTrue(abs(w.difficulty - expected) < 1e-9, "D = ${w.difficulty}")
        assertEquals(123L, w.updatedAt, "Bản sửa tất định không được thắng last-write-wins trước lượt ôn mới của máy khác")
        assertTrue(w.isSynced)
    }

    @Test
    fun theReplayHelperReturnsNullWithoutUsableHistory() = runNow {
        val w = clamped("word_x", reps = 2)
        assertNull(review.replayedDifficultyOrNull(w))
        history("word_x", Rating.GOOD, Rating.EASY)
        assertNotNull(review.replayedDifficultyOrNull(w))
        // Phiên ôn vẫn có giá trị lùi về D0(Tốt) để TÍNH lượt này (không ghi vào thẻ)
        assertEquals(FsrsScheduler.initialDifficulty(Rating.GOOD), review.replayedDifficulty(clamped("word_none", reps = 2)))
    }
}
