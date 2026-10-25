package com.truyencloud.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncStatusTextTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun describe(loggedIn: Boolean, state: SyncStatus.State) = SyncStatusText.describe(loggedIn, state, now)

    @Test
    fun guestSeesNotLoggedIn() {
        val line = describe(false, SyncStatus.State())
        assertEquals("Chưa đăng nhập", line.title)
        assertEquals(SyncTone.NEUTRAL, line.tone)
    }

    @Test
    fun expiredSessionAsksToLogInAgain() {
        val line = describe(false, SyncStatus.State(sessionExpired = true, lastError = "x", lastErrorAtMillis = now))
        assertEquals("Phiên hết hạn — đăng nhập lại", line.title)
        assertEquals(SyncTone.ERROR, line.tone)
        assertTrue(line.detail.contains("giữ nguyên"))
    }

    @Test
    fun expiryWinsEvenIfLoggedInFlagIsStale() {
        val line = describe(true, SyncStatus.State(sessionExpired = true))
        assertEquals(SyncTone.ERROR, line.tone)
    }

    @Test
    fun neverSyncedYet() {
        val line = describe(true, SyncStatus.State())
        assertEquals("Chưa đồng bộ lần nào", line.title)
        assertEquals(SyncTone.NEUTRAL, line.tone)
    }

    @Test
    fun successShowsRelativeTime() {
        val line = describe(true, SyncStatus.State(lastSuccessAtMillis = now - 5 * minute))
        assertEquals("Đã đồng bộ", line.title)
        assertEquals("Lần cuối: 5 phút trước", line.detail)
        assertEquals(SyncTone.OK, line.tone)
    }

    @Test
    fun laterErrorWinsOverEarlierSuccess() {
        val line = describe(
            true,
            SyncStatus.State(lastSuccessAtMillis = now - 3 * hour, lastError = "Không có kết nối mạng.", lastErrorAtMillis = now - minute)
        )
        assertEquals(SyncTone.WARNING, line.tone)
        assertTrue(line.detail.startsWith("Không có kết nối mạng."))
        assertTrue(line.detail.contains("1 phút trước"))
    }

    @Test
    fun laterSuccessClearsOldError() {
        val line = describe(
            true,
            SyncStatus.State(lastSuccessAtMillis = now - minute, lastError = "cũ", lastErrorAtMillis = now - hour)
        )
        assertEquals(SyncTone.OK, line.tone)
    }

    @Test
    fun longErrorsAreTruncated() {
        val line = describe(true, SyncStatus.State(lastError = "x".repeat(500), lastErrorAtMillis = now))
        assertTrue(line.detail.length < 160)
    }

    @Test
    fun relativeTimeBuckets() {
        assertEquals("vừa xong", SyncStatusText.relativeTime(10_000))
        assertEquals("vừa xong", SyncStatusText.relativeTime(-5_000))
        assertEquals("1 phút trước", SyncStatusText.relativeTime(50_000))
        assertEquals("2 phút trước", SyncStatusText.relativeTime(2 * minute))
        assertEquals("59 phút trước", SyncStatusText.relativeTime(59 * minute))
        assertEquals("3 giờ trước", SyncStatusText.relativeTime(3 * hour))
        assertEquals("2 ngày trước", SyncStatusText.relativeTime(49 * hour))
    }
}
