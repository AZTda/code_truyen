package com.truyencloud.shared.data

expect fun getTodayDateString(): String
expect fun getCurrentEpochMillis(): Long

// ---------------------------------------------------------------------------
// Số học ngày tháng dùng chung.
//
// Phần này trước đây nằm riêng trong VocabularyRepository để tính chuỗi ngày học.
// Chuỗi ngày ĐỌC cần đúng logic ấy, nên nó được đưa ra đây thay vì chép lại lần hai
// — hai bản chép rời rồi sẽ lệch nhau.
// Thuật toán ngày Julian rút gọn, không phụ thuộc kotlinx-datetime.
// ---------------------------------------------------------------------------

/** Dịch một khoá ngày dạng YYYY-MM-DD đi [deltaDays] ngày. Chuỗi sai định dạng được trả về nguyên vẹn. */
fun shiftDateKey(dateKey: String, deltaDays: Int): String {
    val parts = dateKey.split("-")
    if (parts.size != 3) return dateKey
    val y = parts[0].toIntOrNull() ?: return dateKey
    val m = parts[1].toIntOrNull() ?: return dateKey
    val d = parts[2].toIntOrNull() ?: return dateKey
    val days = dateToEpochDay(y, m, d) + deltaDays
    val (ny, nm, nd) = epochDayToDate(days)
    return buildString {
        append(ny.toString().padStart(4, '0')); append('-')
        append(nm.toString().padStart(2, '0')); append('-')
        append(nd.toString().padStart(2, '0'))
    }
}

fun dateToEpochDay(y: Int, m: Int, d: Int): Long {
    val a = (14 - m) / 12
    val yy = y + 4800 - a
    val mm = m + 12 * a - 3
    val jdn = d + (153 * mm + 2) / 5 + 365L * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    return jdn - 2440588L
}

fun epochDayToDate(epochDay: Long): Triple<Int, Int, Int> {
    val jdn = epochDay + 2440588L
    val a = jdn + 32044
    val b = (4 * a + 3) / 146097
    val c = a - 146097 * b / 4
    val dd = (4 * c + 3) / 1461
    val e = c - 1461 * dd / 4
    val mm = (5 * e + 2) / 153
    val day = (e - (153 * mm + 2) / 5 + 1).toInt()
    val month = (mm + 3 - 12 * (mm / 10)).toInt()
    val year = (100 * b + dd - 4800 + mm / 10).toInt()
    return Triple(year, month, day)
}

/**
 * Số ngày liên tiếp có hoạt động, tính ngược từ hôm nay.
 *
 * Chuỗi vẫn còn sống nếu hoạt động gần nhất là hôm nay hoặc hôm qua — chưa đọc hôm nay
 * thì chuỗi chưa đứt, nhưng bỏ trọn một ngày thì đứt.
 *
 * @param activeDatesDesc các ngày YYYY-MM-DD có hoạt động, mới nhất trước.
 */
fun computeStreakFromDates(activeDatesDesc: List<String>, today: String): Int {
    if (activeDatesDesc.isEmpty()) return 0
    val yesterday = shiftDateKey(today, -1)
    var cursor = when (activeDatesDesc.first()) {
        today -> today
        yesterday -> yesterday
        else -> return 0
    }
    val set = activeDatesDesc.toSet()
    var streak = 0
    while (set.contains(cursor)) {
        streak++
        cursor = shiftDateKey(cursor, -1)
    }
    return streak
}

/**
 * Đọc mốc thời gian ISO-8601 mà máy chủ trả về thành epoch millis.
 *
 * Chấp nhận "2026-09-19T12:34:56", có hoặc không phần thập phân giây, và hậu tố
 * "Z" hay "+07:00". Trả về null nếu không đọc được — nơi gọi phải tự xử lý,
 * chứ đừng coi chuỗi hỏng là mốc thời gian 0 rồi kết luận nhầm là dữ liệu cũ.
 */
fun parseIsoToEpochMillis(iso: String): Long? {
    val text = iso.trim()
    if (text.length < 19) return null

    val datePart = text.substring(0, 10)
    if (text[10] != 'T' && text[10] != ' ') return null
    val d = datePart.split("-")
    if (d.size != 3) return null
    val year = d[0].toIntOrNull() ?: return null
    val month = d[1].toIntOrNull() ?: return null
    val day = d[2].toIntOrNull() ?: return null

    val timePart = text.substring(11)
    val hh = timePart.substring(0, 2).toIntOrNull() ?: return null
    val mm = timePart.substring(3, 5).toIntOrNull() ?: return null
    val ss = timePart.substring(6, 8).toIntOrNull() ?: return null

    // Phần sau giây: ".123", "Z", "+07:00", "-05:00", hoặc rỗng (coi như UTC).
    var offsetMinutes = 0
    val rest = timePart.substring(8)
    val tzIndex = rest.indexOfFirst { it == 'Z' || it == '+' || (it == '-') }
    if (tzIndex >= 0 && rest[tzIndex] != 'Z') {
        val sign = if (rest[tzIndex] == '-') -1 else 1
        val tz = rest.substring(tzIndex + 1).replace(":", "")
        if (tz.length >= 2) {
            val oh = tz.substring(0, 2).toIntOrNull() ?: 0
            val om = if (tz.length >= 4) tz.substring(2, 4).toIntOrNull() ?: 0 else 0
            offsetMinutes = sign * (oh * 60 + om)
        }
    }

    val days = dateToEpochDay(year, month, day)
    val millis = days * 86_400_000L + hh * 3_600_000L + mm * 60_000L + ss * 1000L
    return millis - offsetMinutes * 60_000L
}
