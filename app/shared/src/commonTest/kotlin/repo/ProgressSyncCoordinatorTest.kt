package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ApiHttpException
import com.truyencloud.shared.api.ProgressNovelSummary
import com.truyencloud.shared.api.ProgressRemoteResponse
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.FakeChapterDao
import com.truyencloud.shared.data.FakeNovelDao
import com.truyencloud.shared.data.FakeProgressRemote
import com.truyencloud.shared.data.FakeSyncStateDao
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.SyncState
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

class ProgressSyncCoordinatorTest {
    private val novels = FakeNovelDao()
    private val chapters = FakeChapterDao()
    private val state = FakeSyncStateDao()
    private val remote = FakeProgressRemote()
    private val coordinator = ProgressSyncCoordinator(novels, chapters, remote, state, CoroutineScope(Job()))
    private var savedToken = ""

    @BeforeTest
    fun setUp() {
        savedToken = TruyenCloudApi.authToken
        TruyenCloudApi.authToken = "eyJzdWIiOiJ1MSJ9.sig"
        SyncStatus.reset()
    }

    @AfterTest
    fun tearDown() {
        TruyenCloudApi.authToken = savedToken
    }

    private fun local(id: String = "n1", chapter: Int = 5, stamp: Long = 1_000, dirty: Boolean = true, shelf: String = "reading", fav: Boolean = false) =
        Novel(id = id, title = "T", lastReadChapter = chapter, lastReadAt = stamp, syncUpdatedAt = stamp, syncDirty = dirty, shelfName = shelf, isFavorite = fav)

    private fun serverRow(id: String = "n1", chapter: Int = 50, stamp: Long = 9_000, shelf: String = "reading", fav: Boolean = false, payload: Boolean = true) =
        ProgressRemoteResponse(
            novel_id = id, current_chapter = chapter, scroll_position = 0.3f, shelf_name = shelf, is_favorite = fav, updated_at_ms = stamp,
            novel = if (payload) ProgressNovelSummary(id = id, title = "Tên", title_vi = "Tên Việt", total_chapters = 200) else null
        )

    @Test
    fun guestsNeverSync() {
        TruyenCloudApi.authToken = ""
        novels.rows["n1"] = local()
        assertEquals(0, runNow { coordinator.flushDirty() })
        assertEquals(0, runNow { coordinator.syncAll() })
        assertTrue(remote.pushed.isEmpty())
        assertTrue(novels.rows["n1"]!!.syncDirty)
    }

    @Test
    fun dirtyRowIsPushedWithClientTimeAndScrollThenCleared() {
        novels.rows["n1"] = local(chapter = 5, stamp = 1_234, shelf = "completed", fav = true)
        runNow { chapters.insertChaptersIfAbsent(listOf(Chapter("n1_5", "n1", 5, "c", scrollPosition = 0.62f))) }
        assertEquals(1, runNow { coordinator.flushDirty() })
        val sent = remote.pushed.single()
        assertEquals(5, sent.chapter)
        assertEquals(0.62f, sent.scroll)
        assertEquals("completed", sent.shelf)
        assertTrue(sent.favorite)
        assertEquals(1_234L, sent.stamp, "luôn gửi client_updated_at")
        assertFalse(novels.rows["n1"]!!.syncDirty)
        assertTrue(SyncStatus.state.value.lastSuccessAtMillis > 0)
    }

    @Test
    fun cleanRowsAreNotPushed() {
        novels.rows["n1"] = local(dirty = false)
        assertEquals(0, runNow { coordinator.flushDirty() })
        assertTrue(remote.pushed.isEmpty())
    }

    @Test
    fun failedPushKeepsTheDirtyFlagForTheNextAttempt() {
        novels.rows["n1"] = local()
        remote.failWith = ApiHttpException(503, "máy chủ bận")
        assertEquals(0, runNow { coordinator.flushDirty() })
        assertTrue(novels.rows["n1"]!!.syncDirty)
        assertNotNull(SyncStatus.state.value.lastError)
        remote.failWith = null
        assertEquals(1, runNow { coordinator.flushDirty() })
        assertFalse(novels.rows["n1"]!!.syncDirty)
    }

    @Test
    fun sessionExpiryStopsTheFlushAndIsReported() {
        novels.rows["a"] = local("a")
        novels.rows["b"] = local("b")
        remote.failWith = ApiHttpException(401, "hết phiên")
        runNow { coordinator.flushDirty() }
        assertEquals(0, remote.pushed.size, "không thử tiếp các truyện còn lại khi đã hết phiên")
        assertTrue(SyncStatus.state.value.sessionExpired)
        assertTrue(novels.rows.values.all { it.syncDirty })
    }

    @Test
    fun staleReplyAdoptsTheNewerServerRowInsteadOfLosingIt() {
        novels.rows["n1"] = local(chapter = 5, stamp = 1_000, dirty = true)
        remote.server["n1"] = serverRow(chapter = 50, stamp = 9_000, shelf = "completed")
        runNow { coordinator.flushDirty() }
        val n = novels.rows["n1"]!!
        assertEquals(50, n.lastReadChapter)
        assertEquals("completed", n.shelfName)
        assertFalse(n.syncDirty)
        assertEquals(9_000L, n.localStamp)
        assertEquals(0.3f, chapters.rows["n1_50"]?.scrollPosition, "vị trí cuộn của bản máy chủ được mang sang")
    }

    @Test
    fun aJustAddedNovelDoesNotOverwriteRealServerProgress() {
        // Thêm vào tủ trên máy mới (chưa đọc chữ nào) khi máy chủ đã có tiến độ thật.
        novels.rows["n1"] = local(chapter = 0, stamp = 5_000, dirty = true)
        remote.server["n1"] = serverRow(chapter = 42, stamp = 1_000)
        runNow { coordinator.flushDirty() }
        assertTrue(remote.pushed.isEmpty(), "không được ghi đè chương 42 bằng chương 0")
        assertEquals(42, novels.rows["n1"]!!.lastReadChapter)
        assertFalse(novels.rows["n1"]!!.syncDirty)
    }

    @Test
    fun aJustAddedNovelUnknownToTheServerIsPushed() {
        novels.rows["n1"] = local(chapter = 0, stamp = 5_000, dirty = true)
        runNow { coordinator.flushDirty() }
        assertEquals(1, remote.pushed.size)
        assertEquals(0, remote.pushed.single().chapter)
        assertEquals(1, remote.getProgressCalls)
    }

    @Test
    fun removalIsSentAsATombstoneShelfAndForgotten() {
        state.rows[SyncKeys.libraryRemoved("n1")] = SyncState(SyncKeys.libraryRemoved("n1"), longValue = 7_000, textValue = "12")
        runNow { coordinator.flushDirty() }
        val sent = remote.pushed.single()
        assertEquals(SHELF_REMOVED, sent.shelf)
        assertEquals(7_000L, sent.stamp)
        assertEquals(12, sent.chapter)
        assertNull(state.rows[SyncKeys.libraryRemoved("n1")])
    }

    @Test
    fun removalTombstoneIsDroppedIfTheNovelWasAddedBack() {
        novels.rows["n1"] = local(dirty = false)
        state.rows[SyncKeys.libraryRemoved("n1")] = SyncState(SyncKeys.libraryRemoved("n1"), longValue = 7_000)
        runNow { coordinator.flushDirty() }
        assertTrue(remote.pushed.isEmpty())
        assertNull(state.rows[SyncKeys.libraryRemoved("n1")])
    }

    @Test
    fun syncAllPushesFirstThenRebuildsTheShelfFromTheServer() {
        novels.rows["mine"] = local("mine", chapter = 3, stamp = 4_000)
        remote.server["cloud"] = serverRow("cloud", chapter = 77, stamp = 8_000, shelf = "planned", fav = true)
        assertEquals(1, runNow { coordinator.syncAll() })
        assertEquals("mine", remote.pushed.single().novelId, "đẩy thay đổi cục bộ trước khi kéo")
        val cloud = assertNotNull(novels.rows["cloud"])
        assertEquals(77, cloud.lastReadChapter)
        assertEquals("planned", cloud.shelfName)
        assertTrue(cloud.isFavorite)
        assertEquals("Tên Việt", cloud.titleVi)
    }

    @Test
    fun syncAllDoesNotResurrectANovelRemovedHere() {
        state.rows[SyncKeys.libraryRemoved("gone")] = SyncState(SyncKeys.libraryRemoved("gone"), longValue = 9_500)
        // Bản máy chủ của "gone" cũ hơn lần xoá (9_500) nên không được dựng lại, kể cả sau khi bia mộ được đẩy.
        remote.server["gone"] = serverRow("gone", stamp = 2_000)
        runNow { coordinator.syncAll() }
        assertNull(novels.rows["gone"])
    }

    @Test
    fun pullOneRestoresProgressAndReportsTheChapterOnlyWhenItChanged() {
        novels.rows["n1"] = local(chapter = 5, stamp = 1_000, dirty = false)
        remote.server["n1"] = serverRow(chapter = 50, stamp = 9_000)
        assertEquals(50, runNow { coordinator.pullOne("n1", null) })
        assertEquals(50, novels.rows["n1"]!!.lastReadChapter)
        // Gọi lại: không còn gì mới.
        assertNull(runNow { coordinator.pullOne("n1", null) })
    }

    @Test
    fun pullOneNeverAddsANovelTheServerHasNoRowFor() {
        assertNull(runNow { coordinator.pullOne("unknown", Novel(id = "unknown", title = "X")) })
        assertNull(novels.rows["unknown"])
    }

    @Test
    fun pullOneAddsANovelReadElsewhereUsingTheDetailPageMetadata() {
        remote.server["n9"] = serverRow("n9", chapter = 12, stamp = 3_000, payload = false)
        assertEquals(12, runNow { coordinator.pullOne("n9", Novel(id = "n9", title = "Từ trang chi tiết")) })
        assertEquals("Từ trang chi tiết", novels.rows["n9"]?.title)
        assertEquals(12, novels.rows["n9"]?.lastReadChapter)
    }
}
