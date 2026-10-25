package com.truyencloud.shared.reader

import com.truyencloud.shared.data.Novel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollSaveTrackerTest {
    private val novel = Novel(id = "n1", title = "Truyện 1")
    private val other = Novel(id = "n2", title = "Truyện 2")

    private fun at(chapter: Int, position: Float, n: Novel = novel) = ScrollReport(n, chapter, position)

    @Test
    fun scrollingWithinOneChapterJustReplacesThePendingPosition() {
        val tracker = ScrollSaveTracker()
        assertNull(tracker.report(at(1, 0.2f)))
        assertNull(tracker.report(at(1, 0.4f)))
        assertEquals(at(1, 0.4f), tracker.takeUnsaved())
        assertNull(tracker.takeUnsaved(), "đã lấy rồi thì không còn gì chờ")
    }

    @Test
    fun movingToAnotherChapterHandsBackTheUnsavedPositionOfTheChapterJustLeft() {
        val tracker = ScrollSaveTracker()
        tracker.report(at(1, 0.95f))
        // lần báo đầu tiên của chương 2 (bản cũ huỷ lịch lưu của chương 1 ở đây -> mất 0.95)
        assertEquals(at(1, 0.95f), tracker.report(at(2, 0f)))
        assertEquals(at(2, 0f), tracker.takeUnsaved())
    }

    @Test
    fun anAlreadySavedPositionIsNotHandedBackAgain() {
        val tracker = ScrollSaveTracker()
        tracker.report(at(1, 0.95f))
        tracker.takeUnsaved()                            // lịch giãn nhịp đã lưu chương 1
        assertNull(tracker.report(at(2, 0.1f)))
    }

    @Test
    fun theSameChapterNumberOfAnotherNovelIsAnotherChapter() {
        val tracker = ScrollSaveTracker()
        tracker.report(at(1, 0.7f, novel))
        assertEquals(at(1, 0.7f, novel), tracker.report(at(1, 0f, other)))
    }

    @Test
    fun returningToAChapterAfterSavingItsPlaceKeepsOnlyTheLatest() {
        val tracker = ScrollSaveTracker()
        tracker.report(at(1, 0.5f))
        assertEquals(at(1, 0.5f), tracker.report(at(2, 0.3f)))
        assertEquals(at(2, 0.3f), tracker.report(at(1, 0.6f)))
        assertEquals(at(1, 0.6f), tracker.takeUnsaved())
    }

    @Test
    fun positionForKnowsOnlyTheLatestReportedChapter() {
        val tracker = ScrollSaveTracker()
        assertNull(tracker.positionFor("n1", 1))
        tracker.report(at(1, 0.4f))
        assertEquals(0.4f, tracker.positionFor("n1", 1))
        tracker.takeUnsaved()
        assertEquals(0.4f, tracker.positionFor("n1", 1), "đã lưu vẫn là vị trí gần nhất người đọc đã báo")
        assertNull(tracker.positionFor("n1", 2))
        assertNull(tracker.positionFor("n2", 1))
    }

    @Test
    fun flushingWithNothingPendingGivesNothing() {
        assertNull(ScrollSaveTracker().takeUnsaved())
    }

    // ── ReadingProgressPolicy: chương chưa hiện chữ thì không được lưu gì ─────────────────────────────────

    @Test
    fun aPlaceholderChapterNeverOverwritesTheSavedPosition() {
        // "Đọc tiếp" từ Home: bản giữ chỗ có scrollPosition = 0 trong lúc chương (chưa tải về máy) còn đang tải; rời đi lúc này
        // từng ghi 0 đè lên 0.8 đã lưu — rồi đẩy 0 lên đám mây
        assertNull(ReadingProgressPolicy.positionOnLeave(contentShown = false, reported = null, restored = 0f))
        assertNull(ReadingProgressPolicy.positionOnLeave(contentShown = false, reported = 0.4f, restored = 0f))
    }

    @Test
    fun aShownChapterKeepsItsRestoredPlaceWhenTheReaderNeverScrolled() {
        assertEquals(0.8f, ReadingProgressPolicy.positionOnLeave(contentShown = true, reported = null, restored = 0.8f))
    }

    @Test
    fun aScrolledChapterSavesWhereTheReaderGot() {
        assertEquals(0.95f, ReadingProgressPolicy.positionOnLeave(contentShown = true, reported = 0.95f, restored = 0.8f))
        assertEquals(0f, ReadingProgressPolicy.positionOnLeave(contentShown = true, reported = 0f, restored = 0.8f), "cuộn về đầu chương là lựa chọn thật")
    }
}
