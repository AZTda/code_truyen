package com.truyencloud.compose.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Android: nối thẳng vào `OnBackPressedDispatcher` của Activity.
 *
 * Nhờ đó nút Back (và cử chỉ vuốt cạnh màn hình) quay về màn hình trước thay vì
 * thoát hẳn ứng dụng.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
