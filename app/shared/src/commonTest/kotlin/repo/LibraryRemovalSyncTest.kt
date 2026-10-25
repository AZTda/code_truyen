package com.truyencloud.shared.repo

import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.api.ProgressNovelSummary
import com.truyencloud.shared.api.ProgressRemoteResponse
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.FakeChapterDao
import com.truyencloud.shared.data.FakeNovelDao
import com.truyencloud.shared.data.FakeProgressRemote
import com.truyencloud.shared.data.FakeSyncStateDao
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.runNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gỡ truyện khỏi tủ lúc phiên đã hết hạn / đã đăng xuất (giữ dữ liệu): bia mộ vẫn phải được ghi vì dữ liệu còn thuộc về tài
 * khoản đó, nếu không đăng nhập lại cùng tài khoản thì truyện sống lại từ máy chủ.
 */
class LibraryRemovalSyncTest {
    private val novels = FakeNovelDao()
    private val chapters = FakeChapterDao()
    private val state = FakeSyncStateDao()
    private val remote = FakeProgressRemote()
    private val sync = ProgressSyncCoordinator(novels, chapters, remote, state, CoroutineScope(Job()))
    private var savedToken = ""

    private fun repo(dataBelongsToAccount: Boolean) =
        NovelRepository(NovelApi(), novels, chapters, null, state, dataBelongsToAccount = { dataBelongsToAccount })

    @BeforeTest
    fun setUp() {
        savedToken = TruyenCloudApi.authToken
        SyncStatus.reset()
    }

    @AfterTest
    fun tearDown() {
        TruyenCloudApi.authToken = savedToken
    }

    private fun cloudRow() = ProgressRemoteResponse(
        novel_id = "n1", current_chapter = 5, scroll_position = 0f, shelf_name = "reading", is_favorite = false,
        updated_at_ms = 1_000, novel = ProgressNovelSummary(id = "n1", title = "Truyện n1", source_id = "s", source_url = "u")
    )

    @Test
    fun aNovelRemovedWhileTheSessionIsExpiredStaysRemovedAfterReLogin() {
        TruyenCloudApi.authToken = "tokenA"
        remote.server["n1"] = cloudRow()
        runNow { sync.syncAll() }
        assertNotNull(novels.rows["n1"])

        // 401: token bị bỏ nhưng dữ liệu và chủ sở hữu (LAST_USER_ID) được giữ.
        TruyenCloudApi.authToken = ""
        runNow { repo(dataBelongsToAccount = true).removeFromLibrary("n1", deleteOfflineData = false) }
        assertNotNull(state.rows[SyncKeys.libraryRemoved("n1")], "Phải ghi bia mộ dù đang không có token")

        TruyenCloudApi.authToken = "tokenA2"
        runNow { sync.syncAll() }

        assertNull(novels.rows["n1"], "Truyện đã gỡ không được sống lại sau khi đăng nhập lại")
        assertEquals("removed", remote.server.getValue("n1").shelf_name)
        assertNull(state.rows[SyncKeys.libraryRemoved("n1")])
    }

    @Test
    fun aTrueGuestLeavesNoTombstoneSoTheRemovalCannotLeakIntoTheFirstAccount() {
        TruyenCloudApi.authToken = ""
        novels.rows["n1"] = Novel(id = "n1", title = "T", lastReadChapter = 2)
        runNow { repo(dataBelongsToAccount = false).removeFromLibrary("n1", deleteOfflineData = false) }
        assertNull(state.rows[SyncKeys.libraryRemoved("n1")])
    }

    @Test
    fun theOldIdGetsATombstoneOnMigrationWhileExpiredToo() {
        TruyenCloudApi.authToken = ""
        novels.rows["old"] = Novel(id = "old", title = "T", lastReadChapter = 8, lastReadAt = 5, syncUpdatedAt = 5)
        assertTrue(runNow { repo(dataBelongsToAccount = true).migrateNovelId("old", "new") })
        assertNotNull(state.rows[SyncKeys.libraryRemoved("old")])
    }
}
