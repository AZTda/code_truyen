package com.truyencloud.shared.repo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `isForeground` của LifecycleSyncManager: trình đọc dùng để không cộng phút đọc khi ứng dụng ra nền.
 * Không có token đăng nhập trong test nên các hàm chỉ cập nhật cờ rồi thoát (không gọi mạng / đồng bộ).
 */
class LifecycleForegroundTest {

    @Test
    fun aSingleBackgroundEventKeepsTheAppForeground() {
        // Hộp thoại xin quyền / đa cửa sổ: Android chỉ gọi onPause, và KHÔNG gọi lại onAppForeground khi quay về
        LifecycleSyncManager.onAppForeground()
        LifecycleSyncManager.onAppBackground()
        assertTrue(LifecycleSyncManager.isForeground.value)
    }

    @Test
    fun twoBackgroundEventsInARowMeanTheAppLeftTheScreen() {
        LifecycleSyncManager.onAppForeground()
        LifecycleSyncManager.onAppBackground() // onPause
        LifecycleSyncManager.onAppBackground() // onStop
        assertFalse(LifecycleSyncManager.isForeground.value)
    }

    @Test
    fun comingBackToTheForegroundResetsTheFlagEvenForGuests() {
        LifecycleSyncManager.onAppForeground()
        LifecycleSyncManager.onAppBackground()
        LifecycleSyncManager.onAppBackground()
        assertFalse(LifecycleSyncManager.isForeground.value)
        // Khách (chưa đăng nhập) thoát sớm khỏi nhánh đồng bộ nhưng cờ vẫn phải bật lại
        LifecycleSyncManager.onAppForeground()
        assertTrue(LifecycleSyncManager.isForeground.value)
    }

    @Test
    fun aLoneBackgroundEventAfterReturningDoesNotPairWithTheOldOne() {
        LifecycleSyncManager.onAppForeground()
        LifecycleSyncManager.onAppBackground()
        LifecycleSyncManager.onAppBackground()
        LifecycleSyncManager.onAppForeground()
        // Sự kiện lẻ mới sau khi đã quay lại không được ghép với cặp cũ
        LifecycleSyncManager.onAppBackground()
        assertTrue(LifecycleSyncManager.isForeground.value)
    }
}
