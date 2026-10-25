package com.truyencloud.shared.repo

import com.truyencloud.shared.data.DailyReviewStat
import com.truyencloud.shared.data.ReviewLogDao
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.data.getTodayDateString
import com.truyencloud.shared.data.shiftDateKey
import com.truyencloud.shared.srs.FsrsScheduler
import com.truyencloud.shared.srs.StudyDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

class VocabularyStatsCalculator(
    private val reviewLogDao: ReviewLogDao?,
    initialConfig: StudyConfig
) {
    private val configFlow = MutableStateFlow(initialConfig)

    var currentConfig: StudyConfig
        get() = configFlow.value
        set(value) { configFlow.value = value }
    init {
        ActivityStreak.register(reviews = reviewLogDao)
    }

    private fun startOfStudyDay(): Long = StudyDay.startOfToday(getCurrentEpochMillis())

    /** Chuỗi ngày chung (đọc HOẶC ôn) — cùng hàm với Trang chủ và Insights. */
    suspend fun computeStreak(): StreakInfo = ActivityStreak.compute(reviews = reviewLogDao)

    suspend fun computeStreakDays(): Int = computeStreak().days

    /** Tỉ lệ nhớ: chỉ tính lượt ôn mà thẻ đang ở trạng thái Ôn tập (xem RETENTION_ELIGIBLE trong ReviewLogDao). */
    suspend fun computeRetention(days: Int = 30): Double {
        val dao = reviewLogDao ?: return 0.0
        val since = getCurrentEpochMillis() - days * FsrsScheduler.MILLIS_PER_DAY
        val total = dao.totalCountSince(since)
        if (total == 0) return 0.0
        return dao.successCountSince(since).toDouble() / total
    }

    /** Số lượt trả lời có đổi lịch hôm nay (luyện tập không tính). */
    suspend fun reviewedTodayCount(): Int =
        reviewLogDao?.countScheduledSince(startOfStudyDay()) ?: 0

    suspend fun newIntroducedToday(): Int =
        reviewLogDao?.countNewIntroducedSince(startOfStudyDay()) ?: 0

    fun dailyStats(days: Int = 30): Flow<List<DailyReviewStat>> {
        val dao = reviewLogDao ?: return flow { emit(emptyList()) }
        return dao.dailyStats(shiftDateKey(getTodayDateString(), -(days - 1)))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun studySnapshot(
        allWordsFlow: Flow<List<SavedWord>>,
        dueWordsFlow: Flow<List<SavedWord>>
    ): Flow<StudySnapshot> {
        // Mốc đầu ngày học đổi lúc 4 giờ sáng — kiểm tra mỗi phút để ứng dụng mở xuyên đêm vẫn đúng.
        val dayStart = flow {
            while (true) {
                emit(startOfStudyDay())
                delay(60_000L)
            }
        }.distinctUntilChanged()

        return dayStart.flatMapLatest { start ->
            val scheduled = reviewLogDao?.observeScheduledSince(start) ?: flowOf(0)
            val introduced = reviewLogDao?.observeNewIntroducedSince(start) ?: flowOf(0)
            combine(allWordsFlow, dueWordsFlow, scheduled, introduced, configFlow) { all, due, scheduledToday, newToday, cfg ->
                val reviewsDone = (scheduledToday - newToday).coerceAtLeast(0)
                val newAvailable = all.count { it.isNeverStudied && !it.isSuspended }
                // Mục tiêu = đã ôn + còn lại. Bản cũ tính mục tiêu từ số thẻ đến hạn CÒN LẠI nên mục tiêu
                // tự co lại mỗi khi ôn một thẻ, thanh tiến độ không bao giờ tới 100% đúng nghĩa.
                val remaining = StudyQueueBuilder.remaining(due.size, newAvailable, reviewsDone, newToday, cfg)
                val streak = computeStreak()
                StudySnapshot(
                    dueCount = due.size,
                    newCount = newAvailable,
                    reviewedToday = scheduledToday,
                    dailyGoal = scheduledToday + remaining,
                    streakDays = streak.days,
                    retention30d = computeRetention(),
                    totalWords = all.size,
                    matureWords = all.count { it.stability >= 21.0 },
                    leechWords = all.count { it.isLeech },
                    reviewsDoneToday = reviewsDone,
                    newIntroducedToday = newToday,
                    streakShields = streak.shields
                )
            }
        }
    }
}
