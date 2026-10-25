package com.truyencloud.compose.platform

import androidx.compose.runtime.Composable

/**
 * Giữ màn hình luôn sáng khi [enabled] (màn đọc). Tự trả về trạng thái cũ khi rời composition
 * hoặc khi [enabled] đổi thành false.
 */
@Composable
expect fun KeepScreenOn(enabled: Boolean)
