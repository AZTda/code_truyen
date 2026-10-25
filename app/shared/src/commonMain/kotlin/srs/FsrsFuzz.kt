package com.truyencloud.shared.srs

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Xáo nhẹ khoảng cách ôn theo bảng fuzz của FSRS (fsrs4anki / py-fsrs).
 *
 * Không có bước này, 30 từ lưu cùng một buổi đọc sẽ đến hạn cùng một ngày mãi mãi
 * và dồn thành một cục. Hệ số xáo được băm từ id thẻ + số lần ôn nên luôn tất định:
 * xem trước khoảng cách và kết quả thật trùng nhau, kiểm thử cũng ổn định.
 */
object FsrsFuzz {

    /** Chỉ xáo khi khoảng cách từ ngưỡng này trở lên. */
    const val MIN_FUZZ_INTERVAL_DAYS = 3L

    private data class FuzzRange(val start: Double, val end: Double, val factor: Double)

    private val RANGES = listOf(
        FuzzRange(2.5, 7.0, 0.15),
        FuzzRange(7.0, 20.0, 0.10),
        FuzzRange(20.0, Double.MAX_VALUE, 0.05)
    )

    /** Khoảng [min, max] mà khoảng cách [intervalDays] có thể bị xáo tới. */
    fun fuzzRange(intervalDays: Long, maximumIntervalDays: Long): LongRange {
        val ivl = intervalDays.toDouble()
        var delta = 1.0
        for (r in RANGES) delta += r.factor * max(min(ivl, r.end) - r.start, 0.0)
        val maxIvl = min((ivl + delta).roundToLong(), maximumIntervalDays)
        val minIvl = min(max(2L, (ivl - delta).roundToLong()), maxIvl)
        return minIvl..maxIvl
    }

    fun apply(intervalDays: Long, key: String, salt: Int, maximumIntervalDays: Long): Long {
        if (intervalDays < MIN_FUZZ_INTERVAL_DAYS) return intervalDays
        val range = fuzzRange(intervalDays, maximumIntervalDays)
        val span = range.last - range.first + 1
        val fuzzed = range.first + floor(unitHash(key, salt) * span).toLong()
        return fuzzed.coerceIn(range.first, range.last)
    }

    /**
     * Băm tất định ra số thực trong [0, 1): FNV-1a 64 bit rồi trộn bằng bộ hoàn tất của MurmurHash3.
     * Tự cài thay vì dùng `String.hashCode()` để chắc chắn cho cùng kết quả trên JVM và Kotlin/Native.
     * Bước trộn cuối là bắt buộc: FNV-1a thô chỉ thay đổi ở các bit THẤP khi hai khoá khác nhau ở ký tự cuối,
     * trong khi ta lấy các bit CAO — kết quả là "word_1".."word_7" cùng ra một giá trị.
     */
    fun unitHash(key: String, salt: Int): Double {
        var h: ULong = 14695981039346656037uL // FNV offset basis
        for (ch in key) {
            h = h xor ch.code.toULong()
            h *= 1099511628211uL // FNV prime
        }
        h = h xor salt.toULong()
        h *= 1099511628211uL
        h = h xor (h shr 33)
        h *= 0xff51afd7ed558ccduL
        h = h xor (h shr 33)
        h *= 0xc4ceb9fe1a85ec53uL
        h = h xor (h shr 33)
        // Lấy 53 bit cao để có số thực phân bố đều.
        return (h shr 11).toDouble() / 9007199254740992.0
    }
}
