package com.truyencloud.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals

class BookmarkMergeTest {
    private fun decide(local: Long?, localDeleted: Boolean = false, remote: Long, remoteDeleted: Boolean = false) =
        BookmarkMerge.decide(local, localDeleted, remote, remoteDeleted)

    @Test
    fun unknownRemoteBookmarkIsInserted() {
        assertEquals(BookmarkMergeAction.TAKE_REMOTE, decide(null, remote = 10))
    }

    @Test
    fun tombstoneOfSomethingNeverSeenIsIgnored() {
        assertEquals(BookmarkMergeAction.IGNORE, decide(null, remote = 10, remoteDeleted = true))
    }

    @Test
    fun newerRemoteEditWins() {
        assertEquals(BookmarkMergeAction.TAKE_REMOTE, decide(100, remote = 200))
    }

    @Test
    fun newerLocalEditIsKept() {
        assertEquals(BookmarkMergeAction.KEEP_LOCAL, decide(300, remote = 200))
    }

    @Test
    fun newerRemoteTombstoneDeletesLocal() {
        assertEquals(BookmarkMergeAction.DELETE_LOCAL, decide(100, remote = 200, remoteDeleted = true))
    }

    @Test
    fun newerLocalEditSurvivesOlderRemoteTombstone() {
        assertEquals(BookmarkMergeAction.KEEP_LOCAL, decide(300, remote = 200, remoteDeleted = true))
    }

    @Test
    fun tombstoneWinsTies() {
        assertEquals(BookmarkMergeAction.DELETE_LOCAL, decide(200, remote = 200, remoteDeleted = true))
        assertEquals(BookmarkMergeAction.KEEP_LOCAL, decide(200, localDeleted = true, remote = 200))
        assertEquals(BookmarkMergeAction.KEEP_LOCAL, decide(200, remote = 200))
    }

    @Test
    fun newerRemoteEditResurrectsLocalTombstone() {
        assertEquals(BookmarkMergeAction.TAKE_REMOTE, decide(100, localDeleted = true, remote = 200))
    }

    @Test
    fun legacyRemoteWithoutTimestampLosesToAnyRealEdit() {
        assertEquals(BookmarkMergeAction.KEEP_LOCAL, decide(5, remote = 0))
        assertEquals(BookmarkMergeAction.TAKE_REMOTE, decide(null, remote = 0))
    }

    @Test
    fun cursorFollowsServerClockWithSmallOverlap() {
        assertEquals(98_000L, BookmarkMerge.nextCursor(serverTimeMs = 100_000L, previous = 5L))
        // Đồng hồ máy chủ lùi: con trỏ cũng lùi theo để không bỏ sót.
        assertEquals(8_000L, BookmarkMerge.nextCursor(serverTimeMs = 10_000L, previous = 98_000L))
        // Thiếu header: giữ con trỏ cũ.
        assertEquals(42L, BookmarkMerge.nextCursor(serverTimeMs = 0L, previous = 42L))
        assertEquals(0L, BookmarkMerge.nextCursor(serverTimeMs = 500L, previous = 42L))
    }

    @Test
    fun tombstoneCutoffIsNinetyDays() {
        val now = 100L * 24 * 3600 * 1000
        assertEquals(10L * 24 * 3600 * 1000, BookmarkMerge.tombstoneCutoff(now))
    }

    @Test
    fun pushBatchesStayWithinServerLimit() {
        val chunks = (1..1234).toList().chunked(BookmarkMerge.MAX_PUSH_BATCH)
        assertEquals(listOf(500, 500, 234), chunks.map { it.size })
    }
}
