package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.StatItem
import com.truyencloud.compose.components.StatRow
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.repo.ReadingStats

/**
 * Một hàng số liệu đọc gọn trong một thẻ: tác phẩm, giờ đọc, chương đã đọc, từ đã đọc. Số thật từ bảng reading_sessions;
 * không còn chuỗi ngày, mục tiêu hay cấp độ. Màu và cỡ chữ theo chủ đề (xem [StatRow]).
 */
@Composable
fun UserStatsCard(
    stats: ReadingStats = ReadingStats(),
    modifier: Modifier = Modifier
) {
    StatRow(
        items = listOf(
            StatItem(groupThousands(stats.novelCount), "Tác phẩm"),
            // Tiếng Việt dùng dấu phẩy thập phân: 48,5
            StatItem(stats.totalHoursLabel.replace('.', ','), "Giờ đọc"),
            StatItem(groupThousands(stats.totalChapters), "Chương đọc"),
            StatItem(compactWords(stats.totalWords), "Từ đã đọc")
        ),
        modifier = modifier.padding(horizontal = Spacing.groupInset)
    )
}

/** 1204 -> "1.204" (dấu chấm ngăn hàng nghìn kiểu Việt Nam). */
internal fun groupThousands(n: Int): String {
    val digits = n.toString().removePrefix("-")
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return if (n < 0) "-$grouped" else grouped
}

/** Số từ rút gọn cho ô hẹp: 950 -> "950", 86.000 -> "86k", 1.250.000 -> "1,3M". */
internal fun compactWords(words: Int): String = when {
    words < 1_000 -> words.toString()
    words < 1_000_000 -> "${words / 1_000}k"
    else -> {
        val tenths = (words / 100_000)
        "${tenths / 10},${tenths % 10}M"
    }
}
