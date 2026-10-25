package com.truyencloud.shared.srs

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

actual fun platformUtcOffsetMillis(atEpochMillis: Long): Long {
    val date = NSDate.dateWithTimeIntervalSince1970(atEpochMillis / 1000.0)
    return NSTimeZone.localTimeZone.secondsFromGMTForDate(date) * 1000L
}
