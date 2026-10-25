package com.truyencloud.shared.srs

import java.util.TimeZone

actual fun platformUtcOffsetMillis(atEpochMillis: Long): Long =
    try {
        TimeZone.getDefault().getOffset(atEpochMillis).toLong()
    } catch (_: Exception) {
        0L
    }
