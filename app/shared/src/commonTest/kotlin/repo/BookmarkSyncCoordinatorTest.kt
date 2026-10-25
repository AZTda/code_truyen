package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ApiHttpException
import com.truyencloud.shared.api.BookmarkRemoteItem
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.data.FakeBookmarkDao
import com.truyencloud.shared.data.FakeBookmarkRemote
import com.truyencloud.shared.data.FakeSyncStateDao
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.runNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookmarkSyncCoordinatorTest {
    // Đủ lớn để "91 ngày trước" vẫn là mốc dương hợp lệ.
    private var now = 200L * 24 * 3600 * 1000
    private val dao = FakeBookmarkDao()
    private val remote = FakeBookmarkRemote { now }
    private val state = FakeSyncStateDao()
    private val coordinator = BookmarkSyncCoordinator(dao, remote, state, CoroutineScope(Job()), clock = { now })

    private val tokenU1 = "eyJzdWIiOiJ1MSJ9.sig1"
    private val tokenU2 = "eyJzdWIiOiJ1MiJ9.sig2"
    private var savedToken = ""

    @BeforeTest
    fun setUp() {
        savedToken = TruyenCloudApi.authToken
        TruyenCloudApi.authToken = tokenU1
        SyncStatus.reset()
    }

    @AfterTest
    fun tearDown() {
        TruyenCloudApi.authToken = savedToken
    }

    private fun bm(id: String, updatedAt: Long = now - 1000, deletedAt: Long = 0, dirty: Boolean = true, note: String = "", novel: String = "n1") =
        Bookmark(
            id = id, novelId = novel, chapterNumber = 1, paragraphIndex = 2, selectedText = "văn bản", note = note,
            createdAt = updatedAt, updatedAt = updatedAt, deletedAt = deletedAt, syncDirty = dirty
        )

    private fun remoteItem(id: String, updatedAt: Long, deletedAt: Long = 0, note: String = "từ máy chủ") =
        BookmarkRemoteItem(
            id = id, novel_id = "n1", chapter_number = 1, paragraph_index = 2, selected_text = "văn bản",
            note = note, created_at = 1, updated_at = updatedAt, deleted_at = deletedAt
        )

    @Test
    fun guestNeverTouchesTheNetwork() {
        TruyenCloudApi.authToken = ""
        dao.rows["a"] = bm("a")
        assertEquals(0, runNow { coordinator.sync(force = true) }.getOrThrow())
        assertTrue(remote.pushBatchSizes.isEmpty() && remote.pullSinceValues.isEmpty())
        assertTrue(dao.rows["a"]!!.syncDirty)
    }

    @Test
    fun firstSyncPushesDirtyRowsAndPullsFromZero() {
        dao.rows["a"] = bm("a")
        runNow { coordinator.sync() }.getOrThrow()
        assertEquals(listOf(1), remote.pushBatchSizes)
        assertEquals(listOf(0L), remote.pullSinceValues, "luôn gửi since, 0 ở lần đầu để nhận được bia mộ")
        assertFalse(dao.rows["a"]!!.syncDirty)
        assertNotNull(remote.server["a"])
        // Con trỏ lưu theo tài khoản, lùi 2 giây để chồng lấn.
        assertEquals(now - BookmarkMerge.CURSOR_OVERLAP_MS, state.rows[SyncKeys.bookmarkCursor("u1")]?.longValue)
    }

    @Test
    fun nextSyncWithinThirtySecondsIsSkippedUnlessForced() {
        dao.rows["a"] = bm("a")
        runNow { coordinator.sync() }.getOrThrow()
        dao.rows["b"] = bm("b")
        now += 29_000
        runNow { coordinator.sync() }.getOrThrow()
        assertEquals(1, remote.pullSinceValues.size, "mở chương/lưu dấu trang dồn dập không được đồng bộ dồn dập")
        assertTrue(dao.rows["b"]!!.syncDirty)

        runNow { coordinator.sync(force = true) }.getOrThrow()
        assertEquals(2, remote.pullSinceValues.size)
        assertFalse(dao.rows["b"]!!.syncDirty)
    }

    @Test
    fun secondSyncUsesThePersistedCursor() {
        runNow { coordinator.sync() }.getOrThrow()
        val cursor = state.rows[SyncKeys.bookmarkCursor("u1")]!!.longValue
        now += 31_000
        runNow { coordinator.sync() }.getOrThrow()
        assertEquals(listOf(0L, cursor), remote.pullSinceValues)
    }

    @Test
    fun loginAsAnotherAccountSyncsImmediatelyWithItsOwnCursor() {
        runNow { coordinator.sync() }.getOrThrow()
        TruyenCloudApi.authToken = tokenU2
        now += 1_000
        runNow { coordinator.sync() }.getOrThrow()
        assertEquals(2, remote.pullSinceValues.size)
        assertEquals(0L, remote.pullSinceValues.last(), "tài khoản mới chưa có con trỏ nên kéo từ đầu")
        assertNotNull(state.rows[SyncKeys.bookmarkCursor("u2")])
    }

    @Test
    fun pushesAreChunkedWithinTheServerLimit() {
        repeat(1234) { i -> dao.rows["b$i"] = bm("b$i") }
        runNow { coordinator.sync() }.getOrThrow()
        assertEquals(listOf(500, 500, 234), remote.pushBatchSizes)
        assertTrue(dao.rows.values.none { it.syncDirty })
    }

    @Test
    fun locallyDeletedBookmarkIsPushedAsATombstone() {
        dao.rows["a"] = bm("a", dirty = false)
        val deletedAt = now
        runNow { dao.softDelete("a", deletedAt) }
        assertTrue(dao.rows["a"]!!.isDeleted)
        now += 1_000
        runNow { coordinator.sync() }.getOrThrow()
        val onServer = assertNotNull(remote.server["a"])
        assertTrue(onServer.isTombstone)
        assertEquals(deletedAt, onServer.deleted_at)
        // Máy chủ đã xác nhận bia mộ: không còn hàng nào đang chờ đẩy.
        assertTrue(dao.rows.values.none { it.syncDirty })
    }

    @Test
    fun deletedBookmarksAreHiddenFromEveryDisplayQuery() {
        dao.rows["a"] = bm("a", dirty = false)
        dao.rows["b"] = bm("b", dirty = false)
        runNow { dao.softDelete("a", now) }
        assertEquals(listOf("b"), runNow { dao.getAllBookmarksList() }.map { it.id })
    }

    @Test
    fun confirmedTombstonesOlderThanNinetyDaysArePurged() {
        val old = now - 91L * 24 * 3600 * 1000
        dao.rows["expired"] = bm("expired", updatedAt = old, deletedAt = old, dirty = false)
        val recent = now - 5L * 24 * 3600 * 1000
        dao.rows["recent"] = bm("recent", updatedAt = recent, deletedAt = recent, dirty = false)
        runNow { coordinator.sync() }.getOrThrow()
        assertNull(dao.rows["expired"])
        assertNotNull(dao.rows["recent"], "bia mộ còn mới phải giữ để chắn bản cũ của máy khác")
    }

    @Test
    fun unconfirmedTombstonesAreNeverPurged() {
        val old = now - 91L * 24 * 3600 * 1000
        dao.rows["pending"] = bm("pending", updatedAt = old, deletedAt = old, dirty = true)
        remote.failWith = ApiHttpException(503, "offline")
        runNow { coordinator.sync() }
        assertNotNull(dao.rows["pending"])
    }

    @Test
    fun remoteTombstoneDeletesTheLocalBookmark() {
        dao.rows["a"] = bm("a", updatedAt = now - 5000, dirty = false)
        remote.server["a"] = remoteItem("a", updatedAt = now - 1000, deletedAt = now - 1000)
        runNow { coordinator.sync() }.getOrThrow()
        assertNull(dao.rows["a"])
    }

    @Test
    fun remoteTombstoneForSomethingNeverSeenIsIgnored() {
        remote.server["ghost"] = remoteItem("ghost", updatedAt = now - 1000, deletedAt = now - 1000)
        runNow { coordinator.sync() }.getOrThrow()
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun newerRemoteEditReplacesLocalAndOlderOneDoesNot() {
        dao.rows["a"] = bm("a", updatedAt = now - 5000, dirty = false, note = "cũ")
        dao.rows["b"] = bm("b", updatedAt = now - 100, dirty = false, note = "mới")
        remote.server["a"] = remoteItem("a", updatedAt = now - 1000, note = "máy chủ mới hơn")
        remote.server["b"] = remoteItem("b", updatedAt = now - 9000, note = "máy chủ cũ hơn")
        runNow { coordinator.sync() }.getOrThrow()
        assertEquals("máy chủ mới hơn", dao.rows["a"]!!.note)
        assertEquals("mới", dao.rows["b"]!!.note)
    }

    @Test
    fun newRemoteBookmarkIsInsertedCleanAndInvalidPlaceholdersAreSkipped() {
        remote.server["x"] = remoteItem("x", updatedAt = now - 10)
        remote.server["bad"] = remoteItem("bad", updatedAt = now - 10).copy(novel_id = "_deleted")
        runNow { coordinator.sync() }.getOrThrow()
        val x = assertNotNull(dao.rows["x"])
        assertFalse(x.syncDirty)
        assertEquals("từ máy chủ", x.note)
        assertNull(dao.rows["bad"])
    }

    @Test
    fun staleReplyTriggersAFullPullEvenWithACursor() {
        runNow { coordinator.sync() }.getOrThrow() // có con trỏ
        now += 31_000
        // Máy chủ giữ bản MỚI hơn (giờ máy này chạy chậm): đẩy bị từ chối -> phải kéo lại từ 0 để lấy được nó.
        remote.server["a"] = remoteItem("a", updatedAt = now + 60_000, note = "bản máy chủ")
        dao.rows["a"] = bm("a", updatedAt = now, note = "bản máy này")
        runNow { coordinator.sync() }.getOrThrow()
        assertEquals(0L, remote.pullSinceValues.last())
        assertEquals("bản máy chủ", dao.rows["a"]!!.note)
    }

    @Test
    fun aPoisonedRowDoesNotBlockTheOthersForever() {
        remote.poisonNote = "POISON"
        dao.rows["ok1"] = bm("ok1")
        dao.rows["bad"] = bm("bad", note = "POISON")
        dao.rows["ok2"] = bm("ok2")
        runNow { coordinator.sync() }.getOrThrow()
        assertNotNull(remote.server["ok1"])
        assertNotNull(remote.server["ok2"])
        assertNull(remote.server["bad"])
        assertTrue(dao.rows.values.none { it.syncDirty }, "dòng hỏng bị bỏ đẩy thay vì làm kẹt mọi lần đồng bộ sau")
    }

    @Test
    fun rowsWithoutIdsOrNovelAreNotSentAtAll() {
        dao.rows["weird"] = bm("weird", novel = "")
        dao.rows["fine"] = bm("fine")
        runNow { coordinator.sync() }.getOrThrow()
        assertEquals(listOf(1), remote.pushBatchSizes)
        assertNotNull(remote.server["fine"])
    }

    @Test
    fun legacyRowsWithoutUpdatedAtAreBackfilledFromCreatedAt() {
        dao.rows["old"] = bm("old", updatedAt = 0, note = "cũ").copy(createdAt = 12345L)
        runNow { coordinator.sync() }.getOrThrow()
        assertEquals(12345L, remote.server["old"]!!.updated_at)
    }

    @Test
    fun sessionExpiryIsReportedNotThrown() {
        remote.failWith = ApiHttpException(401, "hết phiên")
        dao.rows["a"] = bm("a")
        val result = runNow { coordinator.sync() }
        assertTrue(result.isFailure)
        assertTrue(SyncStatus.state.value.sessionExpired)
        assertTrue(dao.rows["a"]!!.syncDirty, "đẩy hụt thì giữ cờ để thử lại")
    }

    @Test
    fun aSuccessfulSyncReportsSuccess() {
        runNow { coordinator.sync() }.getOrThrow()
        assertTrue(SyncStatus.state.value.lastSuccessAtMillis > 0)
    }

    @Test
    fun bookmarkUpdatedAtSameMillisecondAsPushStartIsMarkedClean() {
        dao.rows["same_ms"] = bm("same_ms", updatedAt = now, dirty = true)
        runNow { coordinator.sync() }.getOrThrow()
        assertFalse(dao.rows["same_ms"]!!.syncDirty, "Dòng tạo cùng mili-giây với pushStart phải được gỡ syncDirty")
    }
}
