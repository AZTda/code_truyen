package com.truyencloud.shared.data

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

actual fun getTodayDateString(): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter.stringFromDate(NSDate())
}

actual fun getCurrentEpochMillis(): Long =
    ((NSDate().timeIntervalSinceReferenceDate + 978307200.0) * 1000.0).toLong()
