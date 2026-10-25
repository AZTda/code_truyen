package com.truyencloud.shared.srs

/**
 * Độ lệch (ms) giữa giờ địa phương của thiết bị và UTC tại một thời điểm.
 * Tính theo thời điểm chứ không cố định vì múi giờ có thể đổi giờ mùa hè.
 */
expect fun platformUtcOffsetMillis(atEpochMillis: Long): Long

/**
 * "Ngày học" theo kiểu Anki: một ngày bắt đầu lúc 4 giờ sáng giờ địa phương.
 *
 * Vì sao: hạn ôn trước đây là `now + n * 24h`, nên từ ôn lúc 21 giờ sẽ đến hạn lúc
 * 21 giờ hôm sau — sáng mở ứng dụng ra vẫn chưa thấy thẻ. Làm tròn về đầu ngày học
 * thì mọi thẻ của một ngày cùng xuất hiện từ sáng, và phiên học lúc 1-2 giờ khuya
 * vẫn được tính cho "hôm qua".
 */
object StudyDay {
    const val DEFAULT_DAY_START_HOUR = 4
    private const val HOUR_MS = 3_600_000L
    const val DAY_MS = 24L * HOUR_MS

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }

    /** Số thứ tự ngày học chứa thời điểm [epochMillis]. */
    fun dayIndex(
        epochMillis: Long,
        utcOffsetMillis: Long = platformUtcOffsetMillis(epochMillis),
        dayStartHour: Int = DEFAULT_DAY_START_HOUR
    ): Long = floorDiv(epochMillis + utcOffsetMillis - dayStartHour * HOUR_MS, DAY_MS)

    /** Thời điểm (epoch millis) bắt đầu ngày học thứ [dayIndex]. */
    fun startOfDay(
        dayIndex: Long,
        utcOffsetMillis: Long,
        dayStartHour: Int = DEFAULT_DAY_START_HOUR
    ): Long = dayIndex * DAY_MS + dayStartHour * HOUR_MS - utcOffsetMillis

    /** Đầu ngày học hiện tại — mốc để đếm "hôm nay đã ôn bao nhiêu". */
    fun startOfToday(
        nowEpochMillis: Long,
        utcOffsetMillis: Long = platformUtcOffsetMillis(nowEpochMillis),
        dayStartHour: Int = DEFAULT_DAY_START_HOUR
    ): Long = startOfDay(dayIndex(nowEpochMillis, utcOffsetMillis, dayStartHour), utcOffsetMillis, dayStartHour)

    /**
     * Hạn ôn cho khoảng cách [intervalDays] ngày: đầu ngày học thứ (hôm nay + n).
     * Thẻ ôn lúc 23 giờ với khoảng 1 ngày sẽ đến hạn lúc 4 giờ sáng hôm sau.
     */
    fun dueForInterval(
        nowEpochMillis: Long,
        intervalDays: Long,
        utcOffsetMillis: Long = platformUtcOffsetMillis(nowEpochMillis),
        dayStartHour: Int = DEFAULT_DAY_START_HOUR
    ): Long {
        val today = dayIndex(nowEpochMillis, utcOffsetMillis, dayStartHour)
        return startOfDay(today + intervalDays, utcOffsetMillis, dayStartHour)
    }

    /** Thời điểm theo giờ địa phương [hour]:[minute] của ngày lịch chứa [epochMillis]. */
    fun localClockTime(epochMillis: Long, hour: Int, minute: Int, utcOffsetMillis: Long): Long {
        val calendarDay = floorDiv(epochMillis + utcOffsetMillis, DAY_MS)
        return calendarDay * DAY_MS + hour * HOUR_MS + minute * 60_000L - utcOffsetMillis
    }

    /** Số ngày lịch (tính từ 1970-01-01, giờ địa phương) chứa [epochMillis]. */
    fun localCalendarDay(epochMillis: Long, utcOffsetMillis: Long): Long =
        floorDiv(epochMillis + utcOffsetMillis, DAY_MS)
}
