package com.truyencloud.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionGuardTest {

    // ── Máy trạng thái ───────────────────────────────────────────────────────────

    private fun run(start: SessionState, vararg signals: SessionSignal): SessionState =
        signals.fold(start) { state, signal -> SessionStateMachine.reduce(state, signal) }

    @Test
    fun loginActivatesAndLogoutReturnsToGuest() {
        assertEquals(SessionState.ACTIVE, run(SessionState.GUEST, SessionSignal.LOGGED_IN))
        assertEquals(SessionState.GUEST, run(SessionState.ACTIVE, SessionSignal.LOGGED_OUT))
    }

    @Test
    fun rejectedTokenExpiresOnlyAnActiveSession() {
        assertEquals(SessionState.EXPIRED, run(SessionState.ACTIVE, SessionSignal.TOKEN_REJECTED))
        assertEquals(SessionState.GUEST, run(SessionState.GUEST, SessionSignal.TOKEN_REJECTED))
    }

    @Test
    fun concurrentRejectionsExpireOnce() {
        val once = run(SessionState.ACTIVE, SessionSignal.TOKEN_REJECTED)
        val many = run(SessionState.ACTIVE, SessionSignal.TOKEN_REJECTED, SessionSignal.TOKEN_REJECTED, SessionSignal.TOKEN_REJECTED)
        assertEquals(once, many)
        assertEquals(SessionState.EXPIRED, many)
    }

    @Test
    fun reLoginRecoversFromExpiryAndChoosingGuestLeavesIt() {
        assertEquals(SessionState.ACTIVE, run(SessionState.EXPIRED, SessionSignal.LOGGED_IN))
        assertEquals(SessionState.GUEST, run(SessionState.EXPIRED, SessionSignal.LOGGED_OUT))
    }

    @Test
    fun tokenRefreshKeepsSessionActive() {
        assertEquals(SessionState.ACTIVE, run(SessionState.ACTIVE, SessionSignal.LOGGED_IN))
    }

    @Test
    fun fullLifecycle() {
        val end = run(
            SessionState.GUEST,
            SessionSignal.LOGGED_IN,
            SessionSignal.TOKEN_REJECTED,
            SessionSignal.LOGGED_IN,
            SessionSignal.LOGGED_OUT
        )
        assertEquals(SessionState.GUEST, end)
    }

    // ── Khi nào 401 tính là hết hạn ──────────────────────────────────────────────

    @Test
    fun onlyUnauthorizedCounts() {
        assertFalse(SessionExpiryPolicy.isExpiry(403, "t", "t", "/v1/progress"))
        assertFalse(SessionExpiryPolicy.isExpiry(500, "t", "t", "/v1/progress"))
        assertFalse(SessionExpiryPolicy.isExpiry(200, "t", "t", "/v1/progress"))
        assertTrue(SessionExpiryPolicy.isExpiry(401, "t", "t", "/v1/progress"))
    }

    @Test
    fun deletedAccountAnswers404OnMeAndThatIsExpiry() {
        assertTrue(SessionExpiryPolicy.isExpiry(404, "t", "t", "/v1/auth/me"))
        assertFalse(SessionExpiryPolicy.isExpiry(404, "t", "t", "/v1/novels/123"))
        assertFalse(SessionExpiryPolicy.isExpiry(404, null, "", "/v1/auth/me"))
    }

    @Test
    fun guestCallingLoginOnlyEndpointIsNotExpiry() {
        assertFalse(SessionExpiryPolicy.isExpiry(401, null, "", "/v1/progress/sync"))
        assertFalse(SessionExpiryPolicy.isExpiry(401, "", "", "/v1/progress/sync"))
    }

    @Test
    fun wrongPasswordAtLoginIsNotExpiry() {
        assertFalse(SessionExpiryPolicy.isExpiry(401, "t", "t", "/v1/auth/login"))
        assertFalse(SessionExpiryPolicy.isExpiry(401, "t", "t", "/v1/auth/login/"))
    }

    @Test
    fun refreshAndChangePasswordRejectionsAreExpiry() {
        assertTrue(SessionExpiryPolicy.isExpiry(401, "t", "t", "/v1/auth/refresh"))
        assertTrue(SessionExpiryPolicy.isExpiry(401, "t", "t", "/v1/auth/change-password"))
    }

    @Test
    fun lateResponseOfOldSessionDoesNotKillNewSession() {
        assertFalse(SessionExpiryPolicy.isExpiry(401, "old-token", "new-token", "/v1/progress"))
        assertFalse(SessionExpiryPolicy.isExpiry(401, "old-token", "", "/v1/progress"))
    }

    @Test
    fun extractsBearerToken() {
        assertEquals("abc.def", SessionExpiryPolicy.bearerOf("Bearer abc.def"))
        assertEquals("abc.def", SessionExpiryPolicy.bearerOf("bearer   abc.def "))
        assertNull(SessionExpiryPolicy.bearerOf("Basic xyz"))
        assertNull(SessionExpiryPolicy.bearerOf("Bearer "))
        assertNull(SessionExpiryPolicy.bearerOf(null))
    }

    // ── Gia hạn token ────────────────────────────────────────────────────────────

    private val day = SessionRefreshPolicy.INTERVAL_MILLIS

    @Test
    fun refreshIsOncePer24Hours() {
        val t0 = 1_000_000_000_000L
        assertFalse(SessionRefreshPolicy.isDue(true, t0 + day - 1, t0))
        assertTrue(SessionRefreshPolicy.isDue(true, t0 + day, t0))
        assertTrue(SessionRefreshPolicy.isDue(true, t0 + 3 * day, t0))
    }

    @Test
    fun neverRefreshedIsDue() {
        assertTrue(SessionRefreshPolicy.isDue(true, 5_000L, 0L))
    }

    @Test
    fun noTokenNeverRefreshes() {
        assertFalse(SessionRefreshPolicy.isDue(false, 10 * day, 0L))
    }

    @Test
    fun clockSetBackIsTreatedAsDue() {
        assertTrue(SessionRefreshPolicy.isDue(true, 1_000L, 5_000_000L))
    }
}
