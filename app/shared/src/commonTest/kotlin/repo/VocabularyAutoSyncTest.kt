package com.truyencloud.shared.repo

import com.truyencloud.shared.data.runNow
import kotlin.test.Test
import kotlin.test.assertEquals

/** Khách chưa đăng nhập không đồng bộ, nhưng vẫn phải làm mới nội dung nhắc ôn (iOS chụp số thẻ vào thông báo lúc đặt lịch). */
class VocabularyAutoSyncTest {
    private val calls = mutableListOf<String>()

    private fun auto(loggedIn: Boolean, refreshFails: Boolean = false) = VocabularyAutoSync(
        sync = { calls += "sync" },
        refreshReminder = {
            calls += "refresh"
            if (refreshFails) error("hệ điều hành từ chối")
        },
        isLoggedIn = { loggedIn }
    )

    @Test
    fun loggedInUsersSyncThenRefreshTheReminder() = runNow {
        auto(loggedIn = true).run()
        assertEquals(listOf("sync", "refresh"), calls)
    }

    @Test
    fun guestsSkipTheNetworkButStillRefreshTheReminder() = runNow {
        auto(loggedIn = false).run()
        assertEquals(listOf("refresh"), calls)
    }

    @Test
    fun aFailingReminderRefreshNeverBreaksTheSync() = runNow {
        auto(loggedIn = true, refreshFails = true).run()
        assertEquals(listOf("sync", "refresh"), calls)
    }
}
