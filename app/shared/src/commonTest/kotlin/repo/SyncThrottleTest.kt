package com.truyencloud.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncThrottleTest {
    private var now = 1_000_000L
    private fun throttle() = SyncThrottle(30_000L) { now }

    @Test
    fun firstRunIsAlwaysAllowed() {
        assertTrue(throttle().shouldRun())
    }

    @Test
    fun secondRunWithinIntervalIsBlocked() {
        val t = throttle()
        t.markStarted("u1")
        now += 29_999
        assertFalse(t.shouldRun(accountKey = "u1"))
        assertEquals(1L, t.millisUntilNext("u1"))
        now += 1
        assertTrue(t.shouldRun(accountKey = "u1"))
        assertEquals(0L, t.millisUntilNext("u1"))
    }

    @Test
    fun forceIgnoresInterval() {
        val t = throttle()
        t.markStarted("u1")
        assertTrue(t.shouldRun(force = true, accountKey = "u1"))
    }

    @Test
    fun accountChangeRunsImmediatelyAfterLogin() {
        val t = throttle()
        t.markStarted(SyncAccount.GUEST)
        assertTrue(t.shouldRun(accountKey = "u1"))
        assertEquals(0L, t.millisUntilNext("u1"))
    }

    @Test
    fun resetAllowsImmediateRun() {
        val t = throttle()
        t.markStarted("u1")
        t.reset()
        assertTrue(t.shouldRun(accountKey = "u1"))
    }

    @Test
    fun clockSetBackwardsDoesNotBlockSyncForever() {
        val t = throttle()
        t.markStarted("u1")
        now -= 3_600_000L // người dùng chỉnh đồng hồ lùi 1 giờ
        assertTrue(t.shouldRun(accountKey = "u1"))
        assertEquals(0L, t.millisUntilNext("u1"))
    }

    @Test
    fun burstOfCallsRunsOnlyOncePerWindow() {
        // onPause + onStop + mở chương + lưu dấu trang trong vài giây: chỉ một lượt.
        val t = throttle()
        var runs = 0
        repeat(20) {
            if (t.shouldRun(accountKey = "u1")) {
                t.markStarted("u1")
                runs++
            }
            now += 500
        }
        assertEquals(1, runs)
    }
}
