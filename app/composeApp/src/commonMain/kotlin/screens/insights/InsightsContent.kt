package com.truyencloud.compose.screens.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.LargeTitleScaffold
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.screens.home.ReadingHistoryRow
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.library.ReadingHistoryStore

/**
 * Bố cục tab Thống kê: tiêu đề lớn "Thống kê" thu lại khi cuộn, rồi một cột cuộn duy nhất. Thứ tự theo ưu tiên của người
 * đọc: số liệu đọc, hoạt động đọc, sau đó mới tới các biểu đồ từ vựng. Khối nào chưa có dữ liệu thì ẩn hẳn.
 */
@Composable
fun InsightsContent(state: InsightsUiState, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current

    LargeTitleScaffold(title = "Thống kê", modifier = modifier) { padding ->
        when {
            state.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = colors.accent,
                    trackColor = colors.hairline,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(32.dp)
                )
            }

            !state.hasAnyData -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    message = "Chưa có số liệu",
                    subMessage = "Số liệu sẽ xuất hiện khi bạn đọc truyện hoặc lưu từ mới.",
                    icon = TruyenCloudIcons.Insights
                )
            }

            else -> InsightsSections(
                state = state,
                contentPadding = PaddingValues(
                    start = Spacing.pageH,
                    end = Spacing.pageH,
                    top = Spacing.xs,
                    bottom = padding.calculateBottomPadding() + Spacing.lg
                )
            )
        }
    }
}

@Composable
private fun InsightsSections(state: InsightsUiState, contentPadding: PaddingValues) {
    val hasMemoryData = state.totalWords > 0 || state.dailyReviewStats.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.section)
    ) {
        HeroStatsRow(state)

        // Chưa đọc ngày nào trong khoảng này thì bỏ khối: một lưới trống không nói được gì và chỉ gây áp lực
        val activeDays = state.heatmap.count { it.value > 0 }
        if (activeDays > 0) {
            val weeks = (state.heatmap.size + 6) / 7
            InsightsSection(title = "Hoạt động đọc", caption = "$activeDays ngày có đọc trong $weeks tuần qua") {
                ReadingHeatmap(days = state.heatmap)
            }
        }

        val recentHistory = remember { ReadingHistoryStore.shared.all().take(6) }
        if (recentHistory.isNotEmpty()) {
            InsightsSection(title = "Nhật ký đọc gần đây", caption = "Các chương vừa đọc hoặc nghe Audiobook") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentHistory.forEach { item ->
                        ReadingHistoryRow(item = item, onClick = {})
                    }
                }
            }
        }

        if (hasMemoryData) {
            InsightsSection(title = "Tiến độ ghi nhớ") { RetentionAndMatureRow(state) }
        }

        if (state.stabilityDistribution.any { it.count > 0 }) {
            InsightsSection(title = "Phân bố trí nhớ", caption = "Số từ theo thời gian dự kiến còn nhớ") {
                StabilityBarChart(buckets = state.stabilityDistribution)
            }
        }

        if (state.dueForecast.any { it.count > 0 }) {
            InsightsSection(title = "Dự báo 7 ngày tới", caption = "Số từ đến hạn ôn mỗi ngày") {
                DueForecastChart(forecast = state.dueForecast)
            }
        }

        if (state.dailyReviewStats.isNotEmpty()) {
            InsightsSection(title = "Độ chính xác ôn tập", caption = "Các ngày có ôn trong 30 ngày qua") {
                ReviewAccuracyChart(stats = state.dailyReviewStats)
            }
        }

        if (state.leechWords.isNotEmpty()) {
            InsightsSection(title = "Từ cần chú ý", caption = "Những từ bạn đã quên từ 8 lần trở lên") {
                LeechWordsList(words = state.leechWords)
            }
        }

        if (state.grammarPatterns.isNotEmpty()) {
            InsightsSection(title = "Ngữ pháp nâng cao", caption = "Chạm vào một cấu trúc để xem ví dụ trong truyện") {
                GrammarPatternsSection(patterns = state.grammarPatterns)
            }
        }

        val graph = state.wordGraph
        if (graph != null && graph.nodes.isNotEmpty()) {
            InsightsSection(title = "Mạng lưới ngữ nghĩa", caption = "Các từ đã lưu, nối theo nghĩa và chủ đề") {
                WordGraphSection(graph = graph)
            }
        }
    }
}
