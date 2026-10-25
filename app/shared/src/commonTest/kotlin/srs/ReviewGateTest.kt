package com.truyencloud.shared.srs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewGateTest {
    private val day = StudyDay.DAY_MS
    private val now = 20_454L * day + 10 * 3_600_000L
    private val startOfDay = now - 6 * 3_600_000L

    private fun review(due: Long, last: Long, state: CardState = CardState.REVIEW) = MemoryState(
        stability = 5.0, difficulty = 5.0, state = state, reps = 3, lastReviewEpoch = last, dueEpoch = due
    )

    @Test
    fun theMoiKhongBaoGioDenHanChoLuyenTap() {
        val fresh = MemoryState()
        assertFalse(ReviewGate.shouldSchedule(fresh, ReviewPolicy.SCHEDULE_IF_DUE, now, startOfDay))
        assertFalse(ReviewGate.shouldSchedule(fresh.copy(dueEpoch = now - day), ReviewPolicy.SCHEDULE_IF_DUE, now, startOfDay))
    }

    @Test
    fun theOnTapChiGhiLichKhiDenHanVaChuaOnHomNay() {
        val due = review(due = now - day, last = now - 5 * day)
        assertTrue(ReviewGate.shouldSchedule(due, ReviewPolicy.SCHEDULE_IF_DUE, now, startOfDay))

        val notDue = review(due = now + 3 * day, last = now - 2 * day)
        assertFalse(ReviewGate.shouldSchedule(notDue, ReviewPolicy.SCHEDULE_IF_DUE, now, startOfDay))

        val reviewedToday = review(due = now - 1, last = startOfDay + 1)
        assertFalse(ReviewGate.shouldSchedule(reviewedToday, ReviewPolicy.SCHEDULE_IF_DUE, now, startOfDay))
    }

    @Test
    fun theDangOBuocHocNganDenHanLaGhiLichDuDaOnHomNay() {
        val step = review(due = now - 60_000L, last = now - 6 * 60_000L, state = CardState.RELEARNING)
        assertTrue(ReviewGate.shouldSchedule(step, ReviewPolicy.SCHEDULE_IF_DUE, now, startOfDay))
        val notYet = step.copy(dueEpoch = now + 4 * 60_000L)
        assertFalse(ReviewGate.shouldSchedule(notYet, ReviewPolicy.SCHEDULE_IF_DUE, now, startOfDay))
    }

    @Test
    fun chinhSachCungKhongTuyChon() {
        val any = review(due = now + 9 * day, last = now - 1)
        assertTrue(ReviewGate.shouldSchedule(any, ReviewPolicy.SCHEDULE, now, startOfDay))
        assertFalse(ReviewGate.shouldSchedule(any.copy(dueEpoch = now - day), ReviewPolicy.PRACTICE_ONLY, now, startOfDay))
    }
}
