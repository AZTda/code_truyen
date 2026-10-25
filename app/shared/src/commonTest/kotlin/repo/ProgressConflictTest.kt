package com.truyencloud.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressConflictTest {
    private fun snap(stamp: Long, chapter: Int, scroll: Float = 0f) = ProgressSnapshot(stamp, chapter, scroll)

    @Test
    fun remoteWinsWhenLocalMissing() {
        assertEquals(ProgressWinner.REMOTE, ProgressConflict.resolve(null, snap(10, 3)))
    }

    @Test
    fun newerTimestampWinsEvenWithLowerChapter() {
        // Đọc lại từ đầu ở máy này (mới hơn) không bị kéo ngược về chương cuối của máy kia.
        assertEquals(ProgressWinner.LOCAL, ProgressConflict.resolve(snap(200, 1), snap(100, 50)))
        assertEquals(ProgressWinner.REMOTE, ProgressConflict.resolve(snap(100, 50), snap(200, 1)))
    }

    @Test
    fun sameChapterStillAdoptsNewerRemoteScroll() {
        // Lỗi cũ: cùng chương thì bỏ qua bản máy chủ nên chương dài đọc dở quay về vị trí cũ.
        assertEquals(ProgressWinner.REMOTE, ProgressConflict.resolve(snap(100, 7, 0.1f), snap(300, 7, 0.8f)))
    }

    @Test
    fun equalStampFallsBackToChapterThenScroll() {
        assertEquals(ProgressWinner.REMOTE, ProgressConflict.resolve(snap(100, 4, 0.9f), snap(100, 5, 0.1f)))
        assertEquals(ProgressWinner.LOCAL, ProgressConflict.resolve(snap(100, 6, 0.1f), snap(100, 5, 0.9f)))
        assertEquals(ProgressWinner.REMOTE, ProgressConflict.resolve(snap(100, 5, 0.2f), snap(100, 5, 0.6f)))
        assertEquals(ProgressWinner.LOCAL, ProgressConflict.resolve(snap(100, 5, 0.6f), snap(100, 5, 0.2f)))
    }

    @Test
    fun identicalSnapshotsAreSame() {
        assertEquals(ProgressWinner.SAME, ProgressConflict.resolve(snap(100, 5, 0.5f), snap(100, 5, 0.5004f)))
    }

    private fun row(stamp: Long, chapter: Int = 1, shelf: String = "reading", payload: Boolean = true) =
        RemoteRow(snap(stamp, chapter), shelf, payload)

    @Test
    fun newDeviceInsertsNovelsFromServerPayload() {
        assertEquals(RemoteRowAction.INSERT_LOCAL, ProgressConflict.decideRemoteRow(row(50), null, null))
    }

    @Test
    fun remoteRowWithoutPayloadCannotBuildANovel() {
        assertEquals(RemoteRowAction.SKIP, ProgressConflict.decideRemoteRow(row(50, payload = false), null, null))
    }

    @Test
    fun remoteTombstoneNeverInsertsNovel() {
        assertEquals(RemoteRowAction.SKIP, ProgressConflict.decideRemoteRow(row(50, shelf = SHELF_REMOVED), null, null))
    }

    @Test
    fun locallyRemovedNovelStaysRemovedUnlessServerIsNewer() {
        // Xoá ở máy này lúc 100; bản máy chủ cũ hơn (50) không được dựng lại truyện.
        assertEquals(RemoteRowAction.SKIP, ProgressConflict.decideRemoteRow(row(50), null, removedAt = 100))
        // Có người đọc tiếp trên máy khác SAU khi xoá (200 > 100): truyện quay lại theo giờ sửa.
        assertEquals(RemoteRowAction.INSERT_LOCAL, ProgressConflict.decideRemoteRow(row(200), null, removedAt = 100))
    }

    @Test
    fun remoteTombstoneRemovesOlderLocalButNotNewerLocal() {
        val local = snap(100, 10)
        assertEquals(RemoteRowAction.REMOVE_LOCAL, ProgressConflict.decideRemoteRow(row(150, shelf = SHELF_REMOVED), local, null))
        assertEquals(RemoteRowAction.REMOVE_LOCAL, ProgressConflict.decideRemoteRow(row(100, shelf = SHELF_REMOVED), local, null))
        assertEquals(RemoteRowAction.KEEP_LOCAL, ProgressConflict.decideRemoteRow(row(50, shelf = SHELF_REMOVED), local, null))
    }

    @Test
    fun existingLocalAppliesOnlyNewerRemote() {
        val local = snap(100, 10)
        assertEquals(RemoteRowAction.APPLY_REMOTE, ProgressConflict.decideRemoteRow(row(200, chapter = 12), local, null))
        assertEquals(RemoteRowAction.KEEP_LOCAL, ProgressConflict.decideRemoteRow(row(50, chapter = 12), local, null))
    }
}
