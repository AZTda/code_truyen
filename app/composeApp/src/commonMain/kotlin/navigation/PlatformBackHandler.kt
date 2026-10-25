package com.truyencloud.compose.navigation

import androidx.compose.runtime.Composable

/**
 * Xử lý nút Back của hệ thống, khai báo chung cho mọi nền tảng.
 *
 * Android: nối vào `OnBackPressedDispatcher`.
 * iOS: không có khái niệm nút Back cứng nên là hàm rỗng — điều hướng ngược
 *      trên iOS đi qua nút "Quay lại" trong giao diện.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
