package com.truyencloud.shared.repo

import com.truyencloud.shared.api.SyncVocabularyRemoteResponse
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.api.VocabularySyncDataResponse
import com.truyencloud.shared.api.VocabularySyncHttpException
import com.truyencloud.shared.api.VocabularySyncRemote
import com.truyencloud.shared.data.InMemoryReviewLogDao
import com.truyencloud.shared.data.InMemorySavedWordDao
import com.truyencloud.shared.data.ReviewLog
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.SyncKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kiểm thử bộ điều phối đồng bộ (hợp đồng C7) với DAO trong bộ nhớ và máy chủ giả. */
class VocabularySyncCoordinatorTest {
    private val json = Json { encodeDefaults = true }
    private lateinit var words: InMemorySavedWordDao
    private lateinit var logs: InMemoryReviewLogDao
    private lateinit var remote: FakeRemote
    private lateinit var coordinator: VocabularySyncCoordinator
    private var savedToken = ""

    /** Con trỏ kéo được lưu trong sync_state (giả) dưới khoá của tài khoản đang đăng nhập. */
    private val cursorKey get() = SyncKeys.vocabCursor(SyncAccount.keyFor(TruyenCloudApi.authToken))
    private var storedCursor: Long
        get() = words.syncState[cursorKey] ?: 0L
        set(value) { words.syncState[cursorKey] = value }

    @BeforeTest
    fun setUp() {
        savedToken = TruyenCloudApi.authToken
        TruyenCloudApi.authToken = "token"
        SyncStatus.reset()
        words = InMemorySavedWordDao()
        logs = InMemoryReviewLogDao()
        remote = FakeRemote()
        coordinator = VocabularySyncCoordinator(words, logs, remote, VocabularySyncStore(words))
    }

    @AfterTest
    fun tearDown() {
        TruyenCloudApi.authToken = savedToken
    }

    private fun word(id: String, updated: Long, deleted: Long = 0L, synced: Boolean = false) =
        SavedWord(id = id, word = id.removePrefix("word_"), updatedAt = updated, deletedAt = deleted, isSynced = synced)

    private var logIds = 0L

    /** Room cấp id tự tăng duy nhất cho mỗi dòng; đối tượng giả cũng phải vậy. */
    private fun log(wordId: String, at: Long, synced: Boolean = false) =
        ReviewLog(id = ++logIds, wordId = wordId, reviewedAt = at, rating = 3, isSynced = synced)

    private fun el(w: SavedWord): JsonElement = json.encodeToJsonElement(SavedWord.serializer(), w)
    private fun el(l: ReviewLog): JsonElement = json.encodeToJsonElement(ReviewLog.serializer(), l)

    @Test
    fun chuaDangNhapKhongGoiMayChuVaKhongBaoThanhCong() = runSuspend {
        TruyenCloudApi.authToken = ""
        words.rows += word("word_a", 10)
        val result = coordinator.sync(userInitiated = true)
        assertIs<VocabularySyncResult.NotLoggedIn>(result)
        assertTrue(remote.pushes.isEmpty() && remote.pulls.isEmpty())
        assertEquals(0L, SyncStatus.state.value.lastSuccessAtMillis, "Chưa đăng nhập không được tính là thành công")
        assertEquals("Chưa đăng nhập", SyncStatus.state.value.lastError)
    }

    @Test
    fun chiaLoVaChiDayDongDaDoi() = runSuspend {
        (1..450).forEach { words.rows += word("word_$it", it.toLong()) }       // 450 từ chưa đồng bộ
        words.rows += word("word_old", 5, synced = true)                        // đã đồng bộ, cũ hơn mốc -> không đẩy
        (1..2500).forEach { logs.rows += log("word_1", it.toLong()) }           // 2500 log chưa đồng bộ
        logs.rows += log("word_1", 99_999, synced = true)
        storedCursor = 1_000L                                                   // mốc lần trước

        val result = coordinator.sync(true)
        assertIs<VocabularySyncResult.Success>(result)

        assertEquals(3, remote.pushes.size)
        assertTrue(remote.pushes.all { it.first.size <= VocabularySyncCoordinator.MAX_WORDS_PER_REQUEST && it.second.size <= VocabularySyncCoordinator.MAX_LOGS_PER_REQUEST })
        assertTrue(VocabularySyncCoordinator.MAX_WORDS_PER_REQUEST <= 500 && VocabularySyncCoordinator.MAX_LOGS_PER_REQUEST <= 2000, "Phải nằm trong giới hạn của máy chủ")
        assertEquals(450, remote.pushes.sumOf { it.first.size })
        assertEquals(2500, remote.pushes.sumOf { it.second.size })
        assertFalse(remote.pushes.flatMap { it.first }.any { it.id == "word_old" })
        assertTrue(words.rows.all { it.isSynced }, "Sau khi đẩy xong, mọi dòng đã đồng bộ")
        assertTrue(logs.rows.all { it.isSynced })
        assertEquals(SERVER_TIME, storedCursor, "Lưu server_time_ms làm mốc since lần sau")
        assertEquals(listOf(1_000L), remote.pulls, "Kéo với since = mốc cũ")
        assertTrue(SyncStatus.state.value.lastSuccessAtMillis > 0L)
    }

    @Test
    fun lanDauKeoVoiSinceBangKhong() = runSuspend {
        coordinator.sync(true)
        assertEquals(listOf(0L), remote.pulls, "Luôn gửi since (0 ở lần đầu) để nhận bia mộ")
    }

    @Test
    fun keoVeBiaMoXoaTuCucBoVaTuHienThiKhongConNua() = runSuspend {
        words.rows += word("word_run", updated = 100, synced = true)
        remote.pullWords += el(word("word_run", updated = 100, deleted = 500))
        coordinator.sync(true)
        val row = words.rows.single { it.id == "word_run" }
        assertTrue(row.isDeleted, "Bia mộ từ máy khác phải xoá từ này ở máy này")
        assertTrue(row.isSynced)
    }

    @Test
    fun banCucBoMoiHonBiaMoCuThiThang() = runSuspend {
        words.rows += word("word_run", updated = 900, synced = true)
        remote.pullWords += el(word("word_run", updated = 100, deleted = 500))
        coordinator.sync(true)
        assertFalse(words.rows.single().isDeleted, "Lưu lại từ sau khi máy kia xoá => từ vẫn còn")
    }

    @Test
    fun keoVeTuMoiVaKhongGhiDeBanCucBoMoiHon() = runSuspend {
        words.rows += word("word_keep", updated = 900, synced = true).copy(contextMeaningVi = "cục bộ")
        remote.pullWords += el(word("word_keep", updated = 100).copy(contextMeaningVi = "máy chủ cũ"))
        remote.pullWords += el(word("word_new", updated = 50))
        coordinator.sync(true)
        assertEquals("cục bộ", words.rows.single { it.id == "word_keep" }.contextMeaningVi)
        assertNotNull(words.rows.singleOrNull { it.id == "word_new" })
    }

    @Test
    fun logKeoVeKhuTrungTheoWordIdVaReviewedAt() = runSuspend {
        logs.rows += log("word_a", 1000, synced = true).copy(id = 1)
        remote.pullLogs += el(log("word_a", 1000).copy(id = 5))   // đã có -> bỏ
        remote.pullLogs += el(log("word_a", 2000).copy(id = 1))   // id trùng id cục bộ nhưng là lượt ôn KHÁC -> thêm
        coordinator.sync(true)
        assertEquals(listOf(1000L, 2000L), logs.rows.map { it.reviewedAt }.sorted())
        assertEquals(2, logs.rows.map { it.id }.toSet().size, "Mỗi dòng có id riêng do Room cấp")
    }

    @Test
    fun loiMayChuLaThatBaiVaKhongHaCoDaDongBo() = runSuspend {
        words.rows += word("word_a", 10)
        remote.failWith = VocabularySyncHttpException(500, "hỏng")
        val result = coordinator.sync(true)
        assertIs<VocabularySyncResult.Failure>(result)
        assertFalse(result.sessionExpired)
        assertFalse(words.rows.single().isSynced, "Gửi thất bại thì dòng vẫn phải chờ gửi lại")
        assertEquals(0L, storedCursor, "Không lưu mốc khi thất bại")
        assertNotNull(SyncStatus.state.value.lastError)
        assertEquals(0L, SyncStatus.state.value.lastSuccessAtMillis)
    }

    @Test
    fun hetPhienDangNhapBaoRieng() = runSuspend {
        words.rows += word("word_a", 10)
        remote.failWith = VocabularySyncHttpException(401, "hết hạn")
        val result = coordinator.sync(true)
        assertIs<VocabularySyncResult.Failure>(result)
        assertTrue(result.sessionExpired)
        assertTrue(SyncStatus.state.value.sessionExpired)
    }

    @Test
    fun maychuBaoTuCuHonThiKeoLaiTuDau() = runSuspend {
        storedCursor = 5_000L
        words.rows += word("word_a", 10)
        remote.staleIds = listOf("word_a")
        coordinator.sync(true)
        assertEquals(listOf(0L), remote.pulls, "Có từ stale => kéo toàn bộ (since = 0) để chắc chắn nhận bản mới của máy chủ")
    }

    @Test
    fun suaTuTrongLucDangDayThiKhongHaCo() = runSuspend {
        val w = word("word_a", 10)
        words.rows += w
        remote.onPush = { words.rows[0] = words.rows[0].copy(updatedAt = 11) } // người học vừa ôn xong trong lúc đang gửi
        coordinator.sync(true)
        assertFalse(words.rows.single().isSynced, "Bản sửa mới hơn phải ở lại hàng đợi đẩy lần sau")
    }

    @Test
    fun khongCoGiDeGuiThiKhongGoiDay() = runSuspend {
        words.rows += word("word_a", 10, synced = true)
        storedCursor = 10_000L
        coordinator.sync(true)
        assertTrue(remote.pushes.isEmpty())
        assertEquals(1, remote.pulls.size)
        assertNull(SyncStatus.state.value.lastError)
    }

    @Test
    fun dongHoMayNhanhHonMayChuKhongDayLapTuDaDongBo() = runSuspend {
        // Từ đã đồng bộ (isSynced = true) nhưng updatedAt của đồng hồ máy lớn hơn server_time_ms (storedCursor)
        words.rows += word("word_ahead", updated = SERVER_TIME + 120_000L, synced = true)
        storedCursor = SERVER_TIME
        coordinator.sync(true)
        assertTrue(remote.pushes.isEmpty(), "Từ đã isSynced = true không được đẩy lặp khi đồng hồ máy chạy nhanh hơn máy chủ")
    }

    // -----------------------------------------------------------------------
    // Đối tượng giả
    // -----------------------------------------------------------------------

    private companion object {
        const val SERVER_TIME = 1_790_000_000_000L

        /**
         * Chạy hàm suspend ĐỒNG BỘ — đủ cho các đối tượng giả ở đây vì chúng không bao giờ thật sự tạm dừng
         * (DAO giả trả ngay, Mutex không tranh chấp). Tránh phải thêm kotlinx-coroutines-test chỉ cho bài này.
         */
        fun runSuspend(block: suspend () -> Unit) {
            var outcome: Result<Unit>? = null
            block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
            checkNotNull(outcome) { "Hàm suspend đã tạm dừng thật — đối tượng giả phải trả ngay" }.getOrThrow()
        }
    }

    private class FakeRemote : VocabularySyncRemote {
        val pushes = mutableListOf<Pair<List<SavedWord>, List<ReviewLog>>>()
        val pulls = mutableListOf<Long>()
        val pullWords = mutableListOf<JsonElement>()
        val pullLogs = mutableListOf<JsonElement>()
        var failWith: Exception? = null
        var staleIds: List<String> = emptyList()
        var onPush: () -> Unit = {}

        override suspend fun push(words: List<SavedWord>, reviewLogs: List<ReviewLog>): SyncVocabularyRemoteResponse {
            failWith?.let { throw it }
            pushes += words to reviewLogs
            onPush()
            return SyncVocabularyRemoteResponse(
                words_synced = words.size, stale_word_ids = staleIds, words_stale = staleIds.size,
                logs_synced = reviewLogs.size, server_time_ms = SERVER_TIME
            )
        }

        override suspend fun pull(sinceMs: Long): VocabularySyncDataResponse {
            failWith?.let { throw it }
            pulls += sinceMs
            return VocabularySyncDataResponse(words = pullWords.toList(), review_logs = pullLogs.toList(), server_time_ms = SERVER_TIME)
        }
    }
}
