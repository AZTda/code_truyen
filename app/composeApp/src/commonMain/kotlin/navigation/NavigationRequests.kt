package com.truyencloud.compose.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Yêu cầu điều hướng từ NGOÀI giao diện Compose (bấm thông báo chương mới, liên kết sâu...).
 *
 * Giá trị được giữ lại cho tới khi [AppNav] xử lý xong, nên bấm thông báo lúc app còn đang
 * khởi động (khởi động nguội) vẫn mở đúng truyện.
 */
object NavigationRequests {
    private val _openNovelId = MutableStateFlow<String?>(null)
    val openNovelId: StateFlow<String?> = _openNovelId.asStateFlow()

    fun openNovel(novelId: String) {
        if (novelId.isNotBlank()) _openNovelId.value = novelId
    }

    /** Chỉ xoá khi yêu cầu hiện tại vẫn là [novelId] (tránh xoá nhầm yêu cầu mới đến sau). */
    fun consume(novelId: String) {
        if (_openNovelId.value == novelId) _openNovelId.value = null
    }
}
