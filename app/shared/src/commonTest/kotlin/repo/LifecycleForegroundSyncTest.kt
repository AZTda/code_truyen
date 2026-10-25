package com.truyencloud.shared.repo

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.spinUntil
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Từ vựng cũng được kéo mỗi lần ứng dụng quay lại (cùng nhịp giãn cách với tiến độ / dấu trang). Nơi gọi onAppForeground ở tầng
 * nền tảng không giữ kho từ vựng nên nó đăng ký việc này qua registerForegroundSync.
 */
class LifecycleForegroundSyncTest {
    private class Flag {
        @Volatile
        var set = false
    }

    private var savedToken = ""

    @BeforeTest
    fun setUp() {
        savedToken = TruyenCloudApi.authToken
        LifecycleSyncManager.resetThrottles()
    }

    @AfterTest
    fun tearDown() {
        LifecycleSyncManager.unregisterForegroundSync()
        TruyenCloudApi.authToken = savedToken
    }

    @Test
    fun guestsDoNotRunTheForegroundSync() {
        TruyenCloudApi.authToken = ""
        val ran = Flag()
        LifecycleSyncManager.registerForegroundSync { ran.set = true }
        LifecycleSyncManager.onAppForeground()
        assertFalse(spinUntil(timeout = 300.milliseconds) { ran.set }, "Khách không đồng bộ gì cả")
    }

    @Test
    fun loggedInUsersRunTheRegisteredForegroundSyncWhenTheAppReturns() {
        TruyenCloudApi.authToken = "tokenForeground"
        val ran = Flag()
        LifecycleSyncManager.registerForegroundSync { ran.set = true }
        LifecycleSyncManager.onAppForeground()
        assertTrue(spinUntil { ran.set }, "Việc đăng ký phải chạy khi ứng dụng quay lại")
    }
}
