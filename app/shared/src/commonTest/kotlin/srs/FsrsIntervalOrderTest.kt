package com.truyencloud.shared.srs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Khó < Tốt < Dễ, mỗi bậc cách nhau >= 1 ngày. Mỗi nút trước đây được tính độc lập nên thẻ vừa học lại (ôn cách đây vài
 * phút, độ bền gần như không tăng) có thể hiện "1 ngày / 1 ngày / 1 ngày": nút Dễ vô nghĩa.
 */
class FsrsIntervalOrderTest {
    private val day = FsrsScheduler.MILLIS_PER_DAY
    private val minute = 60_000L
    private val t0 = 19_700L * day + 4 * 3_600_000L
    private val plain = FsrsConfig(enableFuzz = false, utcOffsetMillis = 0L)
    private val fuzzed = FsrsConfig(enableFuzz = true, utcOffsetMillis = 0L)

    private fun days(preview: Map<Rating, ReviewOutcome>) = Triple(
        preview.getValue(Rating.HARD).intervalDays,
        preview.getValue(Rating.GOOD).intervalDays,
        preview.getValue(Rating.EASY).intervalDays
    )

    private fun assertStrictlyOrdered(d: Triple<Long, Long, Long>, context: String) {
        assertTrue(d.first < d.second, "$context: Khó ${d.first} phải < Tốt ${d.second}")
        assertTrue(d.second < d.third, "$context: Tốt ${d.second} phải < Dễ ${d.third}")
    }

    @Test
    fun afterAFirstForgetTheThreeButtonsAreNotIdentical() {
        val learning = FsrsScheduler.review(MemoryState(), Rating.AGAIN, t0, plain).state
        val preview = FsrsScheduler.previewIntervals(learning, t0 + 5 * minute, plain)
        assertStrictlyOrdered(days(preview), "Thẻ mới vừa bấm Quên, 5 phút sau")
    }

    @Test
    fun afterALapseTheThreeButtonsAreNotIdentical() {
        var state = MemoryState()
        var now = t0
        repeat(4) {
            state = FsrsScheduler.review(state, Rating.GOOD, now, plain).state
            now = state.dueEpoch
        }
        val relearning = FsrsScheduler.review(state, Rating.AGAIN, now, plain).state
        assertEquals(CardState.RELEARNING, relearning.state)
        val preview = FsrsScheduler.previewIntervals(relearning, now + 5 * minute, plain)
        assertStrictlyOrdered(days(preview), "Thẻ vừa quên, 5 phút sau")
    }

    @Test
    fun previewAndTheRealReviewAgreeOnEveryButton() {
        val learning = FsrsScheduler.review(MemoryState(), Rating.AGAIN, t0, fuzzed, fuzzKey = "word_a").state
        val now = t0 + 5 * minute
        val preview = FsrsScheduler.previewIntervals(learning, now, fuzzed, fuzzKey = "word_a")
        for (rating in Rating.entries) {
            val real = FsrsScheduler.review(learning, rating, now, fuzzed, fuzzKey = "word_a")
            assertEquals(real.intervalDays, preview.getValue(rating).intervalDays, "Nút $rating")
            assertEquals(FsrsScheduler.formatInterval(real, now), FsrsScheduler.formatInterval(preview.getValue(rating), now))
        }
    }

    @Test
    fun orderingHoldsAcrossAGridOfStatesWithAndWithoutFuzz() {
        val cap = plain.maximumIntervalDays
        var checked = 0
        for (config in listOf(plain, fuzzed)) for (state in listOf(CardState.LEARNING, CardState.REVIEW, CardState.RELEARNING)) {
            for (stability in listOf(0.4872, 1.0, 3.0, 10.0, 50.0, 400.0)) for (difficulty in listOf(2.0, 5.0, 9.0)) {
                for (elapsed in listOf(5 * minute, day, 5 * day, 30 * day, 200 * day)) {
                    val current = MemoryState(
                        stability = stability, difficulty = difficulty, state = state, reps = 3, lapses = 1,
                        lastReviewEpoch = t0 - elapsed, dueEpoch = t0
                    )
                    val d = days(FsrsScheduler.previewIntervals(current, t0, config, fuzzKey = "word_grid"))
                    // Ở trần 3 năm thì các nút buộc phải bằng nhau — ngoài trần phải đúng thứ tự.
                    if (d.second < cap) assertTrue(d.first < d.second && d.second < d.third, "$state S=$stability D=$difficulty Δ=$elapsed: $d")
                    else assertTrue(d.first <= d.second && d.second <= d.third, "$state ở trần: $d")
                    checked++
                }
            }
        }
        assertTrue(checked > 500)
    }

    @Test
    fun aNewCardsHardButtonStaysAShortStepAndOnlyGoodBelowEasyApplies() {
        val preview = FsrsScheduler.previewIntervals(MemoryState(), t0, plain)
        assertEquals(0L, preview.getValue(Rating.HARD).intervalDays, "Thẻ mới bấm Khó là bước học 10 phút")
        assertTrue(preview.getValue(Rating.GOOD).intervalDays < preview.getValue(Rating.EASY).intervalDays)
    }

    @Test
    fun theOrderingRuleFollowsTheReferenceImplementation() {
        // hard = min(hard, good); good = max(good, hard + 1); easy = max(easy, good + 1)
        val collapsed = FsrsIntervals.order(hard = 1, good = 1, easy = 1, maxDays = 1095)
        assertEquals(Triple(1L, 2L, 3L), Triple(collapsed.hard, collapsed.good, collapsed.easy))
        val inverted = FsrsIntervals.order(hard = 9, good = 4, easy = 5, maxDays = 1095)
        assertEquals(Triple(4L, 5L, 6L), Triple(inverted.hard, inverted.good, inverted.easy))
        val already = FsrsIntervals.order(hard = 3, good = 10, easy = 30, maxDays = 1095)
        assertEquals(Triple(3L, 10L, 30L), Triple(already.hard, already.good, already.easy))
        val noHard = FsrsIntervals.order(hard = null, good = 4, easy = 4, maxDays = 1095)
        assertNull(noHard.hard)
        assertEquals(Pair(4L, 5L), Pair(noHard.good, noHard.easy))
    }

    @Test
    fun theOrderingNeverExceedsTheMaximumInterval() {
        val atCap = FsrsIntervals.order(hard = 1095, good = 1095, easy = 1095, maxDays = 1095)
        assertEquals(Triple(1095L, 1095L, 1095L), Triple(atCap.hard, atCap.good, atCap.easy))
    }
}
