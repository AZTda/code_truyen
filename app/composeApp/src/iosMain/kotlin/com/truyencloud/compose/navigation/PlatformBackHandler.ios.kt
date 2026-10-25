package com.truyencloud.compose.navigation

import androidx.compose.runtime.Composable

/**
 * iOS không có nút Back cứng — điều hướng ngược đi qua nút "Quay lại" trong giao diện,
 * vốn đã được nối sẵn ở từng màn hình. Hàm này cố ý để rỗng.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Không làm gì
}
