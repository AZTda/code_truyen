package com.truyencloud.shared.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Chờ (quay vòng) tới khi [condition] đúng hoặc hết [timeout] — cho các bài kiểm thử cần việc chạy ở luồng nền thật
 * (Dispatchers.Default) mà không có kotlinx-coroutines-test. @return điều kiện có đúng trước khi hết hạn không.
 */
fun spinUntil(timeout: Duration = 10.seconds, condition: () -> Boolean): Boolean {
    val started = TimeSource.Monotonic.markNow()
    while (!condition()) {
        if (started.elapsedNow() > timeout) return false
    }
    return true
}
