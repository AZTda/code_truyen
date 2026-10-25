package com.truyencloud.compose.screens.insights

import com.truyencloud.shared.data.ReadingSession
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.data.dateToEpochDay
import com.truyencloud.shared.data.epochDayToDate
import com.truyencloud.shared.data.shiftDateKey
import com.truyencloud.shared.srs.StudyDay
import com.truyencloud.shared.srs.platformUtcOffsetMillis

/** Dựng số liệu cho các biểu đồ Insights. Tách khỏi ViewModel để mỗi tệp một trách nhiệm. */
internal object InsightsBuilders {

    private val WEEKDAY_LABELS = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

    /** 0 = Thứ Hai ... 6 = Chủ Nhật. Ngày epoch 0 (1970-01-01) là Thứ Năm. */
    fun weekdayOf(epochDay: Long): Int = (((epochDay + 3) % 7) + 7).toInt() % 7

    fun dayOfWeekFromDate(dateKey: String): Int {
        val parts = dateKey.split("-")
        if (parts.size != 3) return 0
        val y = parts[0].toIntOrNull() ?: return 0
        val m = parts[1].toIntOrNull() ?: return 0
        val d = parts[2].toIntOrNull() ?: return 0
        return weekdayOf(dateToEpochDay(y, m, d))
    }

    /** Heatmap: mỗi ngày ghi số phút đọc. */
    fun buildHeatmap(startDate: String, endDate: String, sessions: List<ReadingSession>): List<HeatmapDay> {
        val sessionMap = sessions.associateBy { it.date }
        val result = mutableListOf<HeatmapDay>()
        var current = startDate
        while (current <= endDate) {
            result.add(
                HeatmapDay(
                    dateKey = current,
                    value = sessionMap[current]?.minutesRead ?: 0,
                    dayOfWeek = dayOfWeekFromDate(current)
                )
            )
            current = shiftDateKey(current, 1)
        }
        return result
    }

    /**
     * Phân bố độ bền theo 7 khoảng — CHỈ các từ đã học (stability > 0). Thẻ mới chưa có trí nhớ để đo nên trước đây
     * bị dồn hết vào cột "< 1 ngày" và làm cột đó phình ra.
     */
    suspend fun buildStabilityDistribution(dao: SavedWordDao): List<StabilityBucket> {
        val ranges = listOf(
            "< 1 ngày" to (0.0 to 1.0),
            "1-3 ngày" to (1.0 to 3.0),
            "3-7 ngày" to (3.0 to 7.0),
            "1-2 tuần" to (7.0 to 14.0),
            "2-4 tuần" to (14.0 to 28.0),
            "1-3 tháng" to (28.0 to 90.0),
            "> 3 tháng" to (90.0 to 99999.0)
        )
        val studied = dao.countStudied()
        return ranges.map { (label, range) ->
            val count = dao.countByStabilityRange(range.first, range.second)
            StabilityBucket(label = label, count = count, fraction = if (studied <= 0) 0f else count.toFloat() / studied)
        }
    }

    /**
     * Dự báo: một cột "Quá hạn" (đến hạn từ các ngày học trước) rồi 7 cột theo NGÀY HỌC LỊCH (hôm nay + 6 ngày tới,
     * ranh giới 4 giờ sáng). Bản cũ dùng cửa sổ 24 giờ trượt từ bây giờ nên thẻ đến hạn lúc 04:00 sáng mai rơi
     * vào cột "hôm nay" hoặc "ngày mai" tuỳ giờ mở ứng dụng, và thẻ quá hạn biến mất khỏi biểu đồ.
     */
    suspend fun buildDueForecast(dao: SavedWordDao, nowMs: Long): List<DueForecastDay> {
        val offset = platformUtcOffsetMillis(nowMs)
        val today = StudyDay.dayIndex(nowMs, offset)
        val overdue = dao.countOverdueBefore(StudyDay.startOfDay(today, offset))
        val days = (0 until 7).map { k ->
            val start = StudyDay.startOfDay(today + k, offset)
            val end = StudyDay.startOfDay(today + k + 1, offset)
            val (y, m, d) = epochDayToDate(today + k)
            DueForecastDay(
                dayLabel = if (k == 0) "Nay" else WEEKDAY_LABELS[weekdayOf(today + k)],
                dateKey = "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}",
                count = dao.dueCountBetween(start, end),
                isToday = k == 0
            )
        }
        return listOf(DueForecastDay(dayLabel = "Quá hạn", dateKey = "", count = overdue, isOverdue = true)) + days
    }
}
