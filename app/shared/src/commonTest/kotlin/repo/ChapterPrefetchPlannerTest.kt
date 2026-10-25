package com.truyencloud.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterPrefetchPlannerTest {
    @Test
    fun prefetchesFiveAheadWhenFarFromTheEnd() {
        assertEquals(listOf(11, 12, 13, 14, 15), ChapterPrefetchPlanner.targets(10, 5, knownLast = 200, totalKnown = true))
    }

    @Test
    fun stopsAtKnownLastChapter() {
        assertEquals(listOf(29), ChapterPrefetchPlanner.targets(28, 5, knownLast = 29, totalKnown = true))
        assertEquals(listOf(28, 29), ChapterPrefetchPlanner.targets(27, 5, knownLast = 29, totalKnown = true))
    }

    @Test
    fun nothingToPrefetchOnTheLastChapter() {
        // Bản cũ vẫn xin N+1..N+5 ở chương cuối; máy chủ từng cào lại cả mục lục cho mỗi chương không tồn tại.
        assertEquals(emptyList(), ChapterPrefetchPlanner.targets(29, 5, knownLast = 29, totalKnown = true))
        assertEquals(emptyList(), ChapterPrefetchPlanner.targets(30, 5, knownLast = 29, totalKnown = true))
    }

    @Test
    fun unknownTotalProbesOnlyTheNextChapter() {
        assertEquals(listOf(6), ChapterPrefetchPlanner.targets(5, 5, knownLast = 0, totalKnown = false))
        assertEquals(listOf(6), ChapterPrefetchPlanner.targets(5, 5, knownLast = 5, totalKnown = false))
    }

    @Test
    fun discoveredEndClampsFurtherPrefetch() {
        assertEquals(listOf(11), ChapterPrefetchPlanner.targets(10, 5, knownLast = 0, totalKnown = false, knownEnd = 12))
        assertEquals(listOf(11, 12), ChapterPrefetchPlanner.targets(10, 5, knownLast = 200, totalKnown = true, knownEnd = 12))
        assertEquals(emptyList(), ChapterPrefetchPlanner.targets(12, 5, knownLast = 200, totalKnown = true, knownEnd = 12))
    }

    @Test
    fun invalidInputsYieldNothing() {
        assertEquals(emptyList(), ChapterPrefetchPlanner.targets(0, 5, 10, true))
        assertEquals(emptyList(), ChapterPrefetchPlanner.targets(3, 0, 10, true))
    }

    @Test
    fun knownLastCombinesLocalTocAndTotal() {
        assertEquals(120, ChapterPrefetchPlanner.knownLast(localLast = 100, totalChapters = 120))
        assertEquals(100, ChapterPrefetchPlanner.knownLast(localLast = 100, totalChapters = 0))
        assertEquals(0, ChapterPrefetchPlanner.knownLast(localLast = 0, totalChapters = 0))
    }

    @Test
    fun translateAheadSkipsFinalTranslationsButUpgradesProvisionalOnes() {
        assertTrue(ChapterPrefetchPlanner.shouldTranslateAhead(hasTranslation = false, isProvisional = false))
        assertTrue(ChapterPrefetchPlanner.shouldTranslateAhead(hasTranslation = true, isProvisional = true))
        assertFalse(ChapterPrefetchPlanner.shouldTranslateAhead(hasTranslation = true, isProvisional = false))
    }

    @Test
    fun endCacheRemembersTheSmallestMissingChapter() {
        val cache = PrefetchEndCache()
        assertNull(cache.get("n"))
        cache.recordMissing("n", 31)
        assertEquals(30, cache.get("n"))
        cache.recordMissing("n", 40) // không nới ra
        assertEquals(30, cache.get("n"))
        cache.recordMissing("n", 1)  // chương 1 không thể là "hết truyện"
        assertEquals(30, cache.get("n"))
        cache.forget("n")
        assertNull(cache.get("n"))
    }
}
