package com.truyencloud.compose.screens.insights

import com.truyencloud.shared.api.EnglishApi
import com.truyencloud.shared.api.WordGraphPayloadItem
import com.truyencloud.shared.data.ReadingSessionDao
import com.truyencloud.shared.data.ReviewLogDao
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.data.getTodayDateString
import com.truyencloud.shared.data.shiftDateKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel cho Reading Insights Dashboard.
 *
 * Tầng presentation thuần — nhận DAO trực tiếp để không cần mở rộng
 * VocabularyRepository hay ReaderRepository (tuân thủ nguyên tắc "chỉ thêm,
 * không chỉnh code có sẵn").
 *
 * Số liệu được tính lại mỗi khi màn hình mở ra ([refresh], gọi từ InsightsScreen): bản cũ chỉ tính MỘT LẦN lúc
 * khởi động nên học xong vẫn thấy số cũ tới khi tắt mở lại ứng dụng.
 */
class InsightsViewModel(
    private val savedWordDao: SavedWordDao,
    private val readingSessionDao: ReadingSessionDao? = null,
    private val reviewLogDao: ReviewLogDao? = null,
    private val englishApi: EnglishApi? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _uiState = MutableStateFlow(InsightsUiState())
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var networkLoadedAt = 0L

    // Không tải trong init: nhiều người không bao giờ mở Insights, tải lúc khởi động chỉ tốn DB + hai lời gọi mạng.
    // InsightsScreen gọi refresh() mỗi lần mở tab.

    /** Tải lại số liệu (an toàn khi gọi liên tiếp: lần mới thay lần đang chạy). */
    fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.Default) { loadInsights() }
    }

    private suspend fun loadInsights() {
        try {
            val today = getTodayDateString()
            val now = getCurrentEpochMillis()

            // ── 1. Số liệu tổng ──
            val allWords = savedWordDao.getAllWordsOnce()
            val matureWords = savedWordDao.countMatureWords()
            val leechWords = savedWordDao.getLeechWords(20)
            val totalMinutes = readingSessionDao?.totalMinutes() ?: 0
            val totalChapters = readingSessionDao?.totalChapters() ?: 0

            // Tỉ lệ nhớ 30 ngày: chỉ lượt ôn thẻ Ôn tập (xem RETENTION_ELIGIBLE trong ReviewLogDao).
            val thirtyDaysAgoMs = now - 30L * 86_400_000L
            val totalReviewCount = reviewLogDao?.totalCountSince(thirtyDaysAgoMs) ?: 0
            val retention30d = if (totalReviewCount > 0) {
                (reviewLogDao?.successCountSince(thirtyDaysAgoMs) ?: 0).toDouble() / totalReviewCount
            } else 0.0

            // ── 2. Heatmap (90 ngày gần nhất) ──
            val ninetyDaysAgo = shiftDateKey(today, -90)
            val sessions = readingSessionDao?.getSessionsInRange(ninetyDaysAgo, today).orEmpty()
            val heatmap = InsightsBuilders.buildHeatmap(ninetyDaysAgo, today, sessions)

            // ── 3-4. Phân bố độ bền + dự báo ──
            val stabilityBuckets = InsightsBuilders.buildStabilityDistribution(savedWordDao)
            val dueForecast = InsightsBuilders.buildDueForecast(savedWordDao, now)

            // ── 5. Độ chính xác ôn tập 30 ngày ──
            val dailyStats = reviewLogDao?.dailyStats(shiftDateKey(today, -30))?.first().orEmpty()

            val previous = _uiState.value
            _uiState.value = InsightsUiState(
                isLoading = false,
                totalWords = allWords.size,
                matureWords = matureWords,
                totalMinutesRead = totalMinutes,
                totalChaptersRead = totalChapters,
                retention30d = retention30d,
                heatmap = heatmap,
                stabilityDistribution = stabilityBuckets,
                dueForecast = dueForecast,
                dailyReviewStats = dailyStats,
                leechWords = leechWords,
                grammarPatterns = previous.grammarPatterns,
                grammarTotalOccurrences = previous.grammarTotalOccurrences,
                wordGraph = previous.wordGraph
            )

            // ── 6-7. Phần gọi mạng (ngữ pháp, đồ thị từ): nặng nên chỉ làm mới sau mỗi 10 phút ──
            if (englishApi != null && now - networkLoadedAt > NETWORK_REFRESH_MS) {
                networkLoadedAt = now
                loadNetworkSections(allWords)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun loadNetworkSections(allWords: List<SavedWord>) {
        val api = englishApi ?: return
        try {
            val stats = api.getGrammarStats(limit = 10)
            _uiState.update {
                it.copy(grammarPatterns = stats.patterns, grammarTotalOccurrences = stats.totalOccurrences)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {}

        try {
            val payload = allWords.take(40).map {
                WordGraphPayloadItem(
                    word = it.word,
                    meaningVi = it.contextMeaningVi,
                    cefrLevel = it.cefrLevel,
                    stage = if (it.stability >= 21.0) "mature" else "learning"
                )
            }
            val graph = api.getWordGraph(words = payload.ifEmpty { null }, limit = 40)
            _uiState.update { it.copy(wordGraph = graph) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    private companion object {
        const val NETWORK_REFRESH_MS = 10 * 60_000L
    }
}
