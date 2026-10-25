package com.truyencloud.shared.repo

import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.srs.CardState
import com.truyencloud.shared.srs.FsrsScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudyQueueBuilderTest {
    private val day = FsrsScheduler.MILLIS_PER_DAY
    private val now = 20_454L * day + 10 * 3_600_000L
    private val config = StudyConfig(newCardsPerDay = 5, maxReviewsPerDay = 10)

    private fun review(id: String, stability: Double, lastDaysAgo: Int, book: String = "") = SavedWord(
        id = id, word = id, stability = stability, difficulty = 5.0,
        fsrsState = CardState.REVIEW.value, reps = 3,
        lastReviewEpoch = now - lastDaysAgo * day, dueEpoch = now - 1, bookTitle = book, createdAt = 1
    )

    private fun fresh(id: String, createdAt: Long, book: String = "") = SavedWord(
        id = id, word = id, fsrsState = CardState.NEW.value, reps = 0,
        dueEpoch = 0, bookTitle = book, createdAt = createdAt
    )

    @Test
    fun theOnDenHanLuonDungTruocTheMoi() {
        val due = listOf(review("r1", 5.0, 6), review("r2", 5.0, 9))
        val news = (1..8).map { fresh("n$it", it.toLong()) }
        val queue = StudyQueueBuilder.build(due, news, 0, 0, now, config)

        assertEquals(listOf("r2", "r1"), queue.take(2).map { it.id }, "Ôn trước, quá hạn nhiều hơn (R thấp hơn) đứng đầu")
        assertEquals(2 + 5, queue.size, "Thẻ mới bị giới hạn ở newCardsPerDay")
        assertTrue(queue.drop(2).all { it.id.startsWith("n") })
    }

    @Test
    fun theMoiDaGioiThieuHomNayDuocTruVaoHanMuc() {
        val news = (1..8).map { fresh("n$it", it.toLong()) }
        assertEquals(2, StudyQueueBuilder.build(emptyList(), news, 0, 3, now, config).size)
        assertEquals(0, StudyQueueBuilder.build(emptyList(), news, 0, 5, now, config).size)
        assertEquals(0, StudyQueueBuilder.build(emptyList(), news, 0, 9, now, config).size, "Không âm")
    }

    @Test
    fun hanMucOnToiDaTruDiSoDaOn() {
        val due = (1..15).map { review("r$it", 5.0, 3 + it) }
        assertEquals(10, StudyQueueBuilder.build(due, emptyList(), 0, 0, now, config).size)
        assertEquals(4, StudyQueueBuilder.build(due, emptyList(), 6, 0, now, config).size)
        assertEquals(0, StudyQueueBuilder.build(due, emptyList(), 10, 0, now, config).size)
    }

    @Test
    fun theMoiKhongChenLenTruocTheSapQuen() {
        // Lỗi cũ: thẻ mới được cộng +0.6 "novelty" và dueEpoch = lúc lưu nên luôn đứng đầu hàng đợi.
        val due = listOf(review("r1", 5.0, 6))
        val queue = StudyQueueBuilder.build(due, listOf(fresh("n1", 1)), 0, 0, now, config)
        assertEquals("r1", queue.first().id)
        assertEquals("n1", queue.last().id)
    }

    @Test
    fun boQuaTheTamHoanVaTheChuaHocNamLanTrongDanhSachDenHan() {
        val suspended = review("s", 5.0, 6).copy(isSuspended = true)
        val neverStudiedButDue = fresh("x", 1).copy(dueEpoch = now - day) // dữ liệu cũ: thẻ mới có dueEpoch = lúc lưu
        val queue = StudyQueueBuilder.build(listOf(suspended, neverStudiedButDue), emptyList(), 0, 0, now, config)
        assertTrue(queue.isEmpty(), "Thẻ tạm hoãn / thẻ chưa học không được lọt vào nhóm ôn: ${queue.map { it.id }}")
    }

    @Test
    fun theTrongCuonDangDocDuocUuTienTrongNhomMoi() {
        val news = listOf(fresh("old", 1, "Khác"), fresh("newer", 2, "Boston"))
        val queue = StudyQueueBuilder.build(emptyList(), news, 0, 0, now, StudyConfig(newCardsPerDay = 1), "Boston")
        assertEquals(listOf("newer"), queue.map { it.id })
    }

    @Test
    fun theOBuocHocNganDenHanDungDauNhomOn() {
        val step = SavedWord(
            id = "step", word = "step", stability = 0.49, difficulty = 7.0,
            fsrsState = CardState.LEARNING.value, reps = 1, lastReviewEpoch = now - 6 * 60_000L, dueEpoch = now - 1
        )
        val older = review("r1", 5.0, 30)
        val queue = StudyQueueBuilder.build(listOf(older, step), emptyList(), 0, 0, now, config)
        assertEquals("step", queue.first().id)
    }

    @Test
    fun mucTieuNgayBangDaOnCongConLai() {
        // Còn lại = min(đến hạn, hạn mức ôn còn) + min(thẻ mới có, hạn mức mới còn)
        assertEquals(10 + 5, StudyQueueBuilder.remaining(40, 20, 0, 0, config))
        assertEquals(4 + 2, StudyQueueBuilder.remaining(4, 20, 6, 3, config))
        assertEquals(0, StudyQueueBuilder.remaining(0, 0, 0, 0, config))
        assertFalse(StudyQueueBuilder.remaining(3, 0, 0, 0, config) < 0)
    }
}
