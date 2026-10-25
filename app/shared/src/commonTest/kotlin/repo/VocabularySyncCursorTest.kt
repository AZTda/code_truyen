package com.truyencloud.shared.repo

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.FakeVocabularyServer
import com.truyencloud.shared.data.InMemoryReviewLogDao
import com.truyencloud.shared.data.InMemorySavedWordDao
import com.truyencloud.shared.data.ReviewLog
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.runNow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Con trỏ kéo từ vựng nằm trong Room (sync_state) nên bị xoá cùng dữ liệu. Bản cũ cất nó ở SharedPreferences: sau "Xóa dữ liệu
 * trên máy" rồi đăng nhập lại, hoặc A -> B -> A, máy chủ chỉ trả phần đổi SAU mốc cũ nên sổ từ không bao giờ được tải lại — và
 * lưu lại một từ sẽ tạo thẻ trắng đè lên thẻ đã học của máy chủ.
 */
class VocabularySyncCursorTest {
    private val words = InMemorySavedWordDao()
    private val logs = InMemoryReviewLogDao()
    private var savedToken = ""

    @BeforeTest
    fun setUp() {
        savedToken = TruyenCloudApi.authToken
        TruyenCloudApi.authToken = "tokenA"
        SyncStatus.reset()
    }

    @AfterTest
    fun tearDown() {
        TruyenCloudApi.authToken = savedToken
    }

    private fun coordinator(server: FakeVocabularyServer, legacy: LegacyCursorSource? = null) =
        VocabularySyncCoordinator(words, logs, server, VocabularySyncStore(words, legacy))

    private fun card(id: String, stability: Double = 80.0, reps: Int = 6) = SavedWord(
        id = id, word = id.removePrefix("word_"), stability = stability, difficulty = 5.0, fsrsState = 2, reps = reps,
        updatedAt = 500, createdAt = 400, isSynced = true
    )

    private fun log(wordId: String, at: Long) = ReviewLog(wordId = wordId, reviewedAt = at, rating = 3, isSynced = true)

    /** Máy chủ có 3 thẻ đã học + 3 lượt ôn từ lâu (đồng hồ máy chủ 1_000), rồi máy này đồng bộ lần đầu lúc 2_000. */
    private fun serverWithHistory(): FakeVocabularyServer {
        val server = FakeVocabularyServer(clock = 1_000)
        listOf("word_a", "word_b", "word_c").forEachIndexed { i, id ->
            server.seed(card(id))
            server.seed(log(id, 100L + i))
        }
        server.clock = 2_000
        return server
    }

    @Test
    fun wipeThenSyncPullsEverythingBack() = runNow {
        val server = serverWithHistory()
        val sync = coordinator(server)

        assertIs<VocabularySyncResult.Success>(sync.sync(userInitiated = true))
        assertEquals(3, words.rows.size)

        // "Xóa dữ liệu trên máy" / đổi tài khoản: wipeUserData xoá cả từ vựng, nhật ký ôn lẫn bảng sync_state.
        words.wipe()
        logs.rows.clear()

        val result = sync.sync(userInitiated = true)
        assertIs<VocabularySyncResult.Success>(result)
        assertEquals(listOf(0L, 0L), server.pulls, "Con trỏ đã bị xoá cùng dữ liệu nên lượt này phải kéo từ đầu")
        assertEquals(3, words.rows.size, "Sổ từ phải được tải lại")
        assertEquals(3, logs.rows.size, "Lịch sử ôn phải được tải lại")
        assertEquals(80.0, words.rows.first { it.id == "word_a" }.stability, "Thẻ đã học phải về nguyên trạng, không phải thẻ trắng")
        assertEquals(6, words.rows.first { it.id == "word_a" }.reps)
    }

    @Test
    fun withoutAWipeTheCursorKeepsPullsIncremental() = runNow {
        val server = serverWithHistory()
        val sync = coordinator(server)
        sync.sync(true)
        sync.sync(true)
        assertEquals(listOf(0L, 2_000L), server.pulls, "Còn dữ liệu trên máy thì chỉ kéo phần mới")
    }

    @Test
    fun aCursorOnAnEmptyMachineIsDistrustedAndPullsFromTheStart() = runNow {
        val server = serverWithHistory()
        // Con trỏ còn đó (vd. dữ liệu bị xoá bằng đường khác) nhưng máy chẳng còn từ nào và nhật ký ôn nào.
        words.syncState[SyncKeys.vocabCursor(SyncAccount.keyFor("tokenA"))] = 2_000L

        coordinator(server).sync(true)

        assertEquals(listOf(0L), server.pulls)
        assertEquals(3, words.rows.size)
    }

    @Test
    fun switchingAccountsBackAndForthRestoresTheFirstAccount() = runNow {
        val serverA = serverWithHistory()
        val serverB = FakeVocabularyServer(clock = 3_000).also { it.seed(card("word_other", stability = 3.0, reps = 1)) }
        serverB.clock = 4_000

        // A đồng bộ, rồi B đăng nhập (A bị xoá), rồi A đăng nhập lại (B bị xoá).
        TruyenCloudApi.authToken = "tokenA"
        coordinator(serverA).sync(true)
        words.wipe(); logs.rows.clear()
        TruyenCloudApi.authToken = "tokenB"
        coordinator(serverB).sync(true)
        assertEquals(listOf("word_other"), words.rows.map { it.id })
        words.wipe(); logs.rows.clear()
        TruyenCloudApi.authToken = "tokenA"
        coordinator(serverA).sync(true)

        assertEquals(setOf("word_a", "word_b", "word_c"), words.rows.map { it.id }.toSet(), "Sổ từ của A phải trở lại")
        assertEquals(3, logs.rows.size)
        assertEquals(listOf(0L, 0L), serverA.pulls)
    }

    @Test
    fun eachAccountHasItsOwnCursor() = runNow {
        val serverA = serverWithHistory()
        val serverB = FakeVocabularyServer(clock = 3_000).also { it.seed(card("word_other")) }
        coordinator(serverA).sync(true)

        // Cùng một cơ sở dữ liệu (còn dữ liệu của A), tài khoản B lần đầu đồng bộ: không được dùng mốc của A.
        TruyenCloudApi.authToken = "tokenB"
        coordinator(serverB).sync(true)

        assertEquals(listOf(0L), serverB.pulls)
        val keyA = SyncKeys.vocabCursor(SyncAccount.keyFor("tokenA"))
        val keyB = SyncKeys.vocabCursor(SyncAccount.keyFor("tokenB"))
        assertEquals(2_000L, words.syncState[keyA])
        assertEquals(3_000L, words.syncState[keyB])
    }

    @Test
    fun aFailedSyncNeverMovesTheCursor() = runNow {
        val server = serverWithHistory()
        val sync = coordinator(server)
        sync.sync(true)
        server.clock = 9_000
        server.failWith = IllegalStateException("mất mạng")
        assertIs<VocabularySyncResult.Failure>(sync.sync(true))
        assertEquals(2_000L, words.syncState[SyncKeys.vocabCursor(SyncAccount.keyFor("tokenA"))])
    }

    // ---- Di trú con trỏ cũ ở SharedPreferences ----

    private class CountingLegacy(private val value: Long) : LegacyCursorSource {
        var takes = 0
        override fun take(accountKey: String): Long { takes++; return value }
    }

    @Test
    fun theLegacyCursorIsMigratedOnceWhenThereIsLocalDataToAnchorIt() = runNow {
        val server = serverWithHistory()
        words.rows += card("word_local")
        val legacy = CountingLegacy(1_500L)
        val sync = coordinator(server, legacy)

        sync.sync(true)
        sync.sync(true)

        assertEquals(1_500L, server.pulls.first(), "Dùng mốc cũ ở lần đầu để khỏi tải lại toàn bộ")
        assertEquals(1, legacy.takes, "Chỉ di trú MỘT lần: lần sau mốc đã nằm trong Room")
        assertEquals(2_000L, server.pulls.last())
    }

    @Test
    fun aLegacyCursorNewerThanEveryLocalRowIsDistrustedAndHealsTheOldBug() = runNow {
        // Máy đã bị xoá dưới bản cũ rồi người dùng lưu lại vài từ: mọi dòng đều MỚI hơn mốc cũ (1_500) -> dữ liệu cũ trên
        // máy chủ chưa từng về, nên phải kéo lại từ đầu thay vì tin mốc.
        val server = serverWithHistory()
        words.rows += card("word_resaved").copy(updatedAt = 1_900)
        val legacy = CountingLegacy(1_500L)

        coordinator(server, legacy).sync(true)

        assertEquals(0L, server.pulls.first())
        assertEquals(1, legacy.takes)
        assertEquals(setOf("word_a", "word_b", "word_c", "word_resaved"), words.rows.map { it.id }.toSet())
    }

    @Test
    fun theLegacyCursorIsDiscardedWhenTheMachineIsEmpty() = runNow {
        val server = serverWithHistory()
        val legacy = CountingLegacy(1_500L)

        coordinator(server, legacy).sync(true)

        assertEquals(listOf(0L), server.pulls, "Máy trống thì mốc cũ chắc chắn đã cũ hơn dữ liệu: kéo từ đầu")
        assertEquals(1, legacy.takes)
        assertTrue(words.rows.isNotEmpty())
    }
}
