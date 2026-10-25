package com.truyencloud.compose.screens.insights

import com.truyencloud.shared.api.GrammarPatternItem
import com.truyencloud.shared.api.WordGraphResponse
import com.truyencloud.shared.data.DailyReviewStat
import com.truyencloud.shared.data.SavedWord

/**
 * Một ngày trên Heatmap — tổng phút đọc hoặc số từ ôn.
 */
data class HeatmapDay(
    val dateKey: String,       // "YYYY-MM-DD"
    val value: Int,            // minutes read or reviews count
    val dayOfWeek: Int = 0     // 0 = Mon … 6 = Sun
)

/**
 * Phân bố Stability — mỗi bucket là một khoảng ngày.
 */
data class StabilityBucket(
    val label: String,         // "< 1 ngày", "1-3 ngày", "3-7 ngày", "1-2 tuần", "2-4 tuần", "1-3 tháng", "> 3 tháng"
    val count: Int,
    val fraction: Float        // 0f..1f relative to the number of STUDIED words
)

/**
 * Dự báo số từ đến hạn: một cột "Quá hạn" rồi 7 ngày học lịch.
 */
data class DueForecastDay(
    val dayLabel: String,      // "Quá hạn", "Nay", "T3", ... "CN"
    val dateKey: String,
    val count: Int,
    val isOverdue: Boolean = false,
    /** Cột của ngày học hôm nay (giao diện nhấn nhẹ nhãn cột này, không so sánh chuỗi "Nay"). */
    val isToday: Boolean = false
)

/**
 * Snapshot toàn bộ dữ liệu Insights — bất biến, thay thế nguyên khối mỗi lần tải xong.
 */
data class InsightsUiState(
    /** Chỉ true ở lần tải ĐẦU TIÊN; các lần làm mới sau giữ nguyên số liệu cũ trên màn hình cho tới khi có số mới. */
    val isLoading: Boolean = true,

    // ── Số liệu tổng ──
    val totalWords: Int = 0,
    val matureWords: Int = 0,
    val totalMinutesRead: Int = 0,
    val totalChaptersRead: Int = 0,
    val retention30d: Double = 0.0,

    // ── Charts ──
    val heatmap: List<HeatmapDay> = emptyList(),
    val stabilityDistribution: List<StabilityBucket> = emptyList(),
    val dueForecast: List<DueForecastDay> = emptyList(),
    val dailyReviewStats: List<DailyReviewStat> = emptyList(),

    // ── Leech Words ──
    val leechWords: List<SavedWord> = emptyList(),

    // ── Grammar Patterns (Feature #4) ──
    val grammarPatterns: List<GrammarPatternItem> = emptyList(),
    val grammarTotalOccurrences: Int = 0,

    // ── Word Relationship Graph (Feature #7) ──
    val wordGraph: WordGraphResponse? = null
) {
    val maturePercent: Float
        get() = if (totalWords <= 0) 0f else (matureWords.toFloat() / totalWords).coerceIn(0f, 1f)

    /** Chưa đọc chương nào, chưa lưu từ nào: hiện trạng thái trống thay vì một trang toàn số 0. */
    val hasAnyData: Boolean
        get() = totalWords > 0 || totalMinutesRead > 0 || totalChaptersRead > 0
}
