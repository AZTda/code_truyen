package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Thống kê: cột biểu đồ (viền / đặc). */
interface AnalyticsIcons {
    val Insights: ImageVector get() = AnalyticsHolder.insights
    val InsightsFilled: ImageVector get() = AnalyticsHolder.insightsFilled
}

private const val BAR_1 = "M3.5 20v-7a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v7Z"
private const val BAR_2 = "M10 20V5a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v15Z"
private const val BAR_3 = "M16.5 20V10a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v10Z"

private object AnalyticsHolder {
    val insights by lazyIcon("Insights", BAR_1, BAR_2, BAR_3)
    val insightsFilled by lazyIcon("InsightsFilled", "b:$BAR_1", "b:$BAR_2", "b:$BAR_3")
}
