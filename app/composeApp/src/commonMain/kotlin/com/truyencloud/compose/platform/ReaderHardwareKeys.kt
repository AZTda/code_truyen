package com.truyencloud.compose.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Cầu nối phím cứng (phím âm lượng) từ Activity sang màn đọc.
 *
 * Màn đọc bật [volumeKeyPagingEnabled] khi đang mở và người dùng bật tùy chọn;
 * Activity chỉ chặn phím âm lượng (không đổi âm lượng) khi cờ này bật.
 */
object ReaderHardwareKeys {
    const val NEXT_PAGE = 1
    const val PREVIOUS_PAGE = -1

    var volumeKeyPagingEnabled: Boolean = false

    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val events: SharedFlow<Int> = _events.asSharedFlow()

    /** Trả true nếu sự kiện đã được màn đọc tiêu thụ (Activity nên nuốt phím). */
    fun onVolumeKey(isVolumeDown: Boolean): Boolean {
        if (!volumeKeyPagingEnabled) return false
        _events.tryEmit(if (isVolumeDown) NEXT_PAGE else PREVIOUS_PAGE)
        return true
    }
}
