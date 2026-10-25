package com.truyencloud.compose.screens.insights

import kotlin.math.roundToInt

/** Định dạng số kiểu Việt Nam cho màn Thống kê: dấu chấm ngăn nghìn, dấu phẩy thập phân. */
internal object InsightsFormat {

    /**
     * 1234 -> "1.234". Từ [compactFrom] trở lên thì rút gọn kiểu [compact] để vừa các ô hẹp (mặc định không bao giờ rút gọn).
     */
    fun count(n: Int, compactFrom: Int = Int.MAX_VALUE): String {
        if (n >= compactFrom) return compact(n)
        val digits = kotlin.math.abs(n).toString()
        val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
        return if (n < 0) "-$grouped" else grouped
    }

    /** Dạng ngắn cho nhãn hẹp trên biểu đồ: 999 -> "999", 1234 -> "1,2k", 12345 -> "12k". */
    fun compact(n: Int): String = when {
        n < 1_000 -> n.toString()
        n < 10_000 -> {
            val tenths = n / 100
            if (tenths % 10 == 0) "${tenths / 10}k" else "${tenths / 10},${tenths % 10}k"
        }
        else -> "${n / 1_000}k"
    }

    /**
     * Số giờ, cắt xuống: dưới 100 giờ giữ một chữ số thập phân (90 phút -> "1,5", 120 -> "2"); từ 100 giờ trở lên bỏ phần
     * thập phân (nhiễu) để con số luôn vừa ô hẹp.
     */
    fun hours(minutes: Int): String {
        val tenths = (minutes / 6).coerceAtLeast(0)
        val whole = tenths / 10
        val fraction = tenths % 10
        return if (fraction == 0 || whole >= 100) count(whole, compactFrom = 100_000) else "$whole,$fraction"
    }

    /** 0.856 -> "86%". */
    fun percent(fraction: Double): String = "${(fraction * 100).roundToInt().coerceIn(0, 100)}%"

    fun percent(fraction: Float): String = percent(fraction.toDouble())

    /** "2026-10-02" -> "02/10". Khoá lạ thì trả nguyên văn. */
    fun dayMonth(dateKey: String): String {
        val parts = dateKey.split("-")
        return if (parts.size == 3) "${parts[2]}/${parts[1]}" else dateKey
    }
}
