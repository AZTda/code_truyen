package com.truyencloud.shared.repo

import com.truyencloud.shared.data.runNow
import com.truyencloud.shared.data.spinUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Đăng xuất / đổi tài khoản phải đẩy nốt thay đổi chưa đồng bộ TRƯỚC khi xoá token và dữ liệu — nhưng không bao giờ được
 * treo, và không bao giờ chặn việc đăng xuất.
 */
class PendingChangesSyncTest {

    private class Source(
        var pending: Int,
        private val onFlush: suspend (Source) -> Unit = { it.pending = 0 }
    ) : PendingSyncSource {
        var flushes = 0
        override suspend fun pendingCount(): Int = pending
        override suspend fun flush() {
            flushes++
            onFlush(this)
        }
    }

    /** flushBounded chạy các nguồn song song ở luồng nền thật (async + withTimeout) nên phải chờ kết quả thật. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun flush(guard: PendingChangesSync): Int {
        val job: Deferred<Int> = CoroutineScope(Dispatchers.Default).async { guard.flushBounded() }
        assertTrue(spinUntil { job.isCompleted }, "flushBounded phải trả về")
        return job.getCompleted()
    }

    @Test
    fun flushPushesEverySourceAndReportsNothingLeftWhenAllSucceed() {
        val progress = Source(pending = 3)
        val bookmarks = Source(pending = 1)
        val vocab = Source(pending = 12)
        val remaining = flush(PendingChangesSync(listOf(progress, bookmarks, vocab)))
        assertEquals(0, remaining)
        assertEquals(listOf(1, 1, 1), listOf(progress, bookmarks, vocab).map { it.flushes })
    }

    @Test
    fun aSourceThatKeepsItsRowsLeavesThemCounted() {
        val offline = Source(pending = 4) { /* mất mạng: không đẩy được gì */ }
        val ok = Source(pending = 2)
        assertEquals(4, flush(PendingChangesSync(listOf(offline, ok))))
    }

    @Test
    fun aThrowingSourceNeverBlocksTheOthersNorTheLogout() {
        val broken = Source(pending = 1) { throw IllegalStateException("503") }
        val ok = Source(pending = 5)
        val remaining = flush(PendingChangesSync(listOf(broken, ok)))
        assertEquals(1, remaining, "Nguồn hỏng vẫn còn 1 mục; nguồn kia đã đẩy xong")
        assertEquals(1, ok.flushes)
    }

    @Test
    fun countingDoesNotTouchTheNetwork() = runNow {
        val s = Source(pending = 7)
        assertEquals(7, PendingChangesSync(listOf(s)).pendingCount())
        assertEquals(0, s.flushes)
    }

    @Test
    fun aHangingFlushIsCutOffAtTheTimeoutSoLogoutCanNeverHang() {
        val hanging = Source(pending = 3) { awaitCancellation() }
        val ok = Source(pending = 2)
        val guard = PendingChangesSync(listOf(hanging, ok), timeoutMs = 100)

        assertEquals(3, flush(guard), "Chỉ phần của nguồn treo còn lại; nguồn kia vẫn được đẩy trong thời hạn")
        assertEquals(1, ok.flushes)
    }

    @Test
    fun theDefaultBudgetIsAboutFiveSeconds() {
        assertEquals(5_000L, PendingChangesSync.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun noticesNameTheNumberOfLostChanges() {
        assertTrue(AccountNotices.lostOnLogout(7).contains("7"))
        assertTrue(AccountNotices.lostOnSwitch(3).contains("3"))
    }
}
