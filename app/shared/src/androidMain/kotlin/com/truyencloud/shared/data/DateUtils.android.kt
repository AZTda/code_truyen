package com.truyencloud.shared.data

import java.time.LocalDate

actual fun getTodayDateString(): String {
    return try {
        LocalDate.now().toString()
    } catch (e: Exception) {
        "1970-01-01"
    }
}

actual fun getCurrentEpochMillis(): Long = System.currentTimeMillis()
