package com.truyencloud.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterNavigationTest {

    @Test
    fun nextStopsAtTheLastKnownChapter() {
        assertEquals(6, ChapterNavigation.nextTarget(current = 5, lastKnown = 120))
        assertEquals(120, ChapterNavigation.nextTarget(current = 119, lastKnown = 120))
        assertNull(ChapterNavigation.nextTarget(current = 120, lastKnown = 120))
        assertNull(ChapterNavigation.nextTarget(current = 121, lastKnown = 120))
    }

    @Test
    fun unknownLastChapterStillAllowsProbingTheNextOne() {
        assertEquals(6, ChapterNavigation.nextTarget(current = 5, lastKnown = 0))
        assertFalse(ChapterNavigation.isLast(current = 5, lastKnown = 0))
    }

    @Test
    fun lastKnownTakesTheLargestSource() {
        assertEquals(300, ChapterNavigation.lastKnown(120, 300, 0))
        assertEquals(0, ChapterNavigation.lastKnown())
        assertEquals(0, ChapterNavigation.lastKnown(-5, 0))
    }

    @Test
    fun checkingForNewChaptersReenablesNext() {
        val before = 120
        val after = 123
        assertEquals(3, ChapterNavigation.newChaptersAdded(before, after))
        assertEquals(0, ChapterNavigation.newChaptersAdded(before, 118))
        assertNull(ChapterNavigation.nextTarget(120, before))
        assertEquals(121, ChapterNavigation.nextTarget(120, after))
    }

    @Test
    fun previousStopsAtChapterOne() {
        assertEquals(4, ChapterNavigation.prevTarget(5))
        assertNull(ChapterNavigation.prevTarget(1))
    }

    @Test
    fun completionIsCountedOnlyAfterTheNextChapterLoaded() {
        assertTrue(ChapterNavigation.shouldCountCompletion(requested = true, loadSucceeded = true))
        assertFalse(ChapterNavigation.shouldCountCompletion(requested = true, loadSucceeded = false))
        assertFalse(ChapterNavigation.shouldCountCompletion(requested = false, loadSucceeded = true))
    }

    @Test
    fun tappingNextWithoutReadingIsNotAFinishedChapter() {
        // Lỗi gốc: Next, Prev, Next, Prev, Next không cuộn gì vẫn ghi 3 chương hoàn thành (và giữ chuỗi ngày đọc)
        assertFalse(ChapterNavigation.hasFinishedReading(finishedReading = false, endReachedKey = null, chapterKey = "abc_5"))
        assertFalse(ChapterNavigation.hasFinishedReading(finishedReading = false, endReachedKey = "abc_4", chapterKey = "abc_5"))
        assertFalse(ChapterNavigation.hasFinishedReading(finishedReading = false, endReachedKey = null, chapterKey = null))
    }

    @Test
    fun reachingTheEndCardOrListeningToTheEndIsAFinishedChapter() {
        assertTrue(ChapterNavigation.hasFinishedReading(finishedReading = false, endReachedKey = "abc_5", chapterKey = "abc_5"))
        assertTrue(ChapterNavigation.hasFinishedReading(finishedReading = true, endReachedKey = null, chapterKey = "abc_5"))
        assertTrue(ChapterNavigation.hasFinishedReading(finishedReading = true, endReachedKey = "abc_4", chapterKey = "abc_5"))
    }

    @Test
    fun theSameChapterNumberOfAnotherNovelIsNotTheShownChapter() {
        // Lỗi gốc: bản dịch chậm của chương 1 truyện A đè lên chương 1 truyện B vừa mở (truyện nào cũng mở ở chương 1)
        assertTrue(ChapterNavigation.isShown(shownNovelId = "A", shownChapter = 1, novelId = "A", chapterNumber = 1))
        assertFalse(ChapterNavigation.isShown(shownNovelId = "B", shownChapter = 1, novelId = "A", chapterNumber = 1))
        assertFalse(ChapterNavigation.isShown(shownNovelId = "A", shownChapter = 2, novelId = "A", chapterNumber = 1))
        assertFalse(ChapterNavigation.isShown(shownNovelId = null, shownChapter = null, novelId = "A", chapterNumber = 1))
    }

    @Test
    fun messageAndKey() {
        assertEquals("Bạn đã đọc hết (hiện có 120 chương)", ChapterNavigation.endOfBookMessage(120))
        assertEquals("abc_7", ChapterNavigation.chapterKey("abc", 7))
    }
}
