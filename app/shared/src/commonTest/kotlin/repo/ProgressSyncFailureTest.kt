package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ApiHttpException
import com.truyencloud.shared.api.ProgressNovelSummary
import com.truyencloud.shared.api.ProgressRemoteResponse
import com.truyencloud.shared.api.TruyenCloudApi
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

/**
 * Đẩy tiến độ khi có lỗi: bia mộ "độc" không chặn tiến độ, hàng di trú chưa có giờ không đè thay đổi mới hơn của máy khác,
 * và trạng thái đồng bộ không báo xanh khi nửa đẩy còn hụt.
 */
class ProgressSyncFailureTest {
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

    private fun local(id: String = "n1", chapter: Int = 5, stamp: Long = 1_000, dirty: Boolean = true) =
        Novel(id = id, title = "T", lastReadChapter = chapter, lastReadAt = stamp, syncUpdatedAt = stamp, syncDirty = dirty)

    private fun serverRow(id: String = "n1", chapter: Int = 50, stamp: Long = 9_000, shelf: String = "reading", fav: Boolean = false) =
        ProgressRemoteResponse(
            novel_id = id, current_chapter = chapter, scroll_position = 0.3f, shelf_name = shelf, is_favorite = fav, updated_at_ms = stamp,
            novel = ProgressNovelSummary(id = id, title = "Tên", title_vi = "Tên Việt", total_chapters = 200)
        )

    // ---- Bia mộ hụt không được chặn việc đẩy tiến độ ----

    @Test
    fun aFailingRemovalTombstoneDoesNotBlockTheDirtyProgressPushes() {
        state.rows[SyncKeys.libraryRemoved("gone")] = SyncState(SyncKeys.libraryRemoved("gone"), longValue = 500, textValue = "3")
        novels.rows["n2"] = local("n2", chapter = 9, stamp = 900)
        remote.failRemovals = ApiHttpException(503, "máy chủ bận")

        val result = runNow { coordinator.flushDirtyDetailed() }

        assertEquals(1, result.pushed, "Tiến độ đang dirty vẫn phải được đẩy")
        assertTrue(result.failed)
        assertEquals(listOf("n2"), remote.pushed.map { it.novelId })
        assertFalse(novels.rows["n2"]!!.syncDirty)
        assertNotNull(state.rows[SyncKeys.libraryRemoved("gone")], "Bia mộ hụt phải ở lại để thử lại lần sau")
    }

    @Test
    fun oneBadTombstoneDoesNotStopTheOtherTombstones() {
        state.rows[SyncKeys.libraryRemoved("bad")] = SyncState(SyncKeys.libraryRemoved("bad"), longValue = 500)
        state.rows[SyncKeys.libraryRemoved("fine")] = SyncState(SyncKeys.libraryRemoved("fine"), longValue = 600)
        remote.failNovels["bad"] = ApiHttpException(503, "máy chủ bận")

        val result = runNow { coordinator.flushDirtyDetailed() }

        assertEquals(1, result.pushed)
        assertTrue(result.failed)
        assertNull(state.rows[SyncKeys.libraryRemoved("fine")], "Bia mộ thứ hai vẫn được báo")
        assertNotNull(state.rows[SyncKeys.libraryRemoved("bad")], "Lỗi tạm thời (503): giữ bia mộ để thử lại")
    }

    @Test
    fun aTombstoneTheServerRejectsOutrightIsDroppedInsteadOfFailingForever() {
        state.rows[SyncKeys.libraryRemoved("poison")] = SyncState(SyncKeys.libraryRemoved("poison"), longValue = 500)
        novels.rows["n2"] = local("n2")
        remote.failNovels["poison"] = ApiHttpException(422, "id không hợp lệ")

        val result = runNow { coordinator.flushDirtyDetailed() }

        assertNull(state.rows[SyncKeys.libraryRemoved("poison")], "Gửi lại y nguyên sẽ không bao giờ được chấp nhận")
        assertFalse(result.failed, "Không để trạng thái đồng bộ đỏ vĩnh viễn vì một bia mộ hỏng")
        assertEquals(1, result.pushed)
        assertEquals(listOf("n2"), remote.pushed.map { it.novelId })
    }

    @Test
    fun sessionExpiryOnATombstoneStopsTheWholeFlush() {
        state.rows[SyncKeys.libraryRemoved("gone")] = SyncState(SyncKeys.libraryRemoved("gone"), longValue = 500)
        novels.rows["n2"] = local("n2")
        remote.failRemovals = ApiHttpException(401, "hết phiên")

        runNow { coordinator.flushDirty() }

        assertTrue(remote.pushed.isEmpty(), "Hết phiên thì mọi lời đẩy sau đó cũng 401: đừng gửi thêm")
        assertTrue(SyncStatus.state.value.sessionExpired)
        assertTrue(novels.rows["n2"]!!.syncDirty)
    }

    // ---- Hàng di trú chưa có giờ nào không được bịa giờ "bây giờ" ----

    private fun legacyRow(id: String = "n9", shelf: String = "planned") =
        Novel(id = id, title = "T", shelfName = shelf, isFavorite = false, lastReadChapter = 0, lastReadAt = 0, syncUpdatedAt = 0, syncDirty = true)

    @Test
    fun anUnstampedLegacyRowNeverOverridesANewerShelfChangeFromAnotherDevice() {
        novels.rows["n9"] = legacyRow()
        // Máy kia (đã nâng cấp) chuyển truyện chưa đọc này sang "completed" và yêu thích.
        remote.server["n9"] = serverRow("n9", chapter = 0, stamp = 5_000, shelf = "completed", fav = true)

        runNow { coordinator.flushDirty() }

        val onServer = remote.server.getValue("n9")
        assertEquals("completed", onServer.shelf_name, "Thay đổi mới hơn của máy kia không bị đè")
        assertTrue(onServer.is_favorite)
        val local = novels.rows.getValue("n9")
        assertEquals("completed", local.shelfName, "Máy này nhận bản của máy chủ")
        assertTrue(local.isFavorite)
        assertFalse(local.syncDirty)
    }

    @Test
    fun anUnstampedLegacyRowUnknownToTheServerIsStillPushedWithTheMinimalStamp() {
        novels.rows["n9"] = legacyRow()

        runNow { coordinator.flushDirty() }

        assertEquals(ProgressSyncCoordinator.UNSTAMPED_LEGACY_MS, remote.pushed.single().stamp, "Không bịa giờ hiện tại cho dữ liệu cũ")
        val local = novels.rows.getValue("n9")
        assertEquals(ProgressSyncCoordinator.UNSTAMPED_LEGACY_MS, local.localStamp)
        assertFalse(local.syncDirty)
    }

    // ---- Trạng thái đồng bộ không báo xanh khi nửa đẩy còn hụt ----

    @Test
    fun aFailedPushIsNotReportedAsSuccessEvenWhenThePullSucceeds() {
        novels.rows["n1"] = local("n1")
        remote.failPushes = ApiHttpException(500, "lỗi máy chủ")
        remote.server["cloud"] = serverRow("cloud", chapter = 3, stamp = 8_000)

        val outcome = runNow { coordinator.syncAllDetailed() }

        assertTrue(outcome.pushFailed)
        assertNull(outcome.error)
        assertFalse(outcome.succeeded)
        assertNotNull(SyncStatus.state.value.lastError, "Lỗi đẩy không được bị lượt kéo thành công xoá đi")
        assertEquals(0L, SyncStatus.state.value.lastSuccessAtMillis)
        assertTrue(novels.rows["n1"]!!.syncDirty)
        assertNotNull(novels.rows["cloud"], "Nửa kéo vẫn chạy")
    }

    @Test
    fun aLaterSuccessDoesNotClearTheErrorOfAnEarlierFailureInTheSameFlush() {
        novels.rows["a"] = local("a")
        novels.rows["b"] = local("b")
        remote.failNovels["a"] = ApiHttpException(500, "lỗi máy chủ")

        val result = runNow { coordinator.flushDirtyDetailed() }

        assertEquals(1, result.pushed)
        assertTrue(result.failed)
        assertNotNull(SyncStatus.state.value.lastError)
        assertEquals(0L, SyncStatus.state.value.lastSuccessAtMillis)
    }

    @Test
    fun syncAllReportsSuccessOnlyWhenEverythingWentThrough() {
        novels.rows["n1"] = local("n1")
        val outcome = runNow { coordinator.syncAllDetailed() }
        assertTrue(outcome.succeeded)
        assertTrue(SyncStatus.state.value.lastSuccessAtMillis > 0)
    }

    @Test
    fun aFailedPullIsReturnedToTheCallerInsteadOfBeingSwallowed() {
        remote.failWith = ApiHttpException(503, "máy chủ bận")
        val outcome = runNow { coordinator.syncAllDetailed() }
        assertNotNull(outcome.error)
        assertFalse(outcome.succeeded)
        assertEquals(0, outcome.changed)
    }

    @Test
    fun pendingCountCoversDirtyRowsAndUnreportedRemovals() {
        novels.rows["a"] = local("a")
        novels.rows["b"] = local("b", dirty = false)
        state.rows[SyncKeys.libraryRemoved("gone")] = SyncState(SyncKeys.libraryRemoved("gone"), longValue = 5)
        assertEquals(2, runNow { coordinator.pendingCount() })
    }
}
