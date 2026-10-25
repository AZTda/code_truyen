package com.truyencloud.shared.repo

import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.prefs.PrefKeys
import com.truyencloud.shared.prefs.SettingsStore
import com.truyencloud.shared.srs.CardState
import com.truyencloud.shared.srs.MemoryState

/** Cấu hình phiên học do người dùng chỉnh. */
data class StudyConfig(
    val newCardsPerDay: Int = 12,
    val maxReviewsPerDay: Int = 80,
    val desiredRetention: Double = 0.90
) {
    fun save(store: SettingsStore?) {
        val s = store ?: return
        s.putInt(PrefKeys.NEW_CARDS_PER_DAY, newCardsPerDay.coerceIn(1, 200))
        s.putInt(PrefKeys.MAX_REVIEWS_PER_DAY, maxReviewsPerDay.coerceIn(10, 500))
        s.putFloat(PrefKeys.DESIRED_RETENTION, desiredRetention.toFloat().coerceIn(0.75f, 0.97f))
    }

    companion object {
        fun load(store: SettingsStore?, fallback: StudyConfig = StudyConfig()): StudyConfig {
            val s = store ?: return fallback
            return StudyConfig(
                newCardsPerDay = s.getInt(PrefKeys.NEW_CARDS_PER_DAY, fallback.newCardsPerDay).coerceIn(1, 200),
                maxReviewsPerDay = s.getInt(PrefKeys.MAX_REVIEWS_PER_DAY, fallback.maxReviewsPerDay).coerceIn(10, 500),
                desiredRetention = s.getFloat(PrefKeys.DESIRED_RETENTION, fallback.desiredRetention.toFloat())
                    .toDouble().coerceIn(0.75, 0.97)
            )
        }
    }
}

/** Ảnh chụp tiến độ học hôm nay (ngày học bắt đầu lúc 4 giờ sáng, xem StudyDay). */
data class StudySnapshot(
    /** Thẻ ĐÃ HỌC đang đến hạn (không gồm thẻ mới). */
    val dueCount: Int = 0,
    /** Thẻ chưa từng học, còn đang chờ được giới thiệu. */
    val newCount: Int = 0,
    /** Số lượt trả lời CÓ đổi lịch FSRS hôm nay (luyện tập không tính). */
    val reviewedToday: Int = 0,
    /** = đã ôn hôm nay + số thẻ còn lại trong hàng đợi — không còn tự co lại khi người học ôn dần. */
    val dailyGoal: Int = 0,
    val streakDays: Int = 0,
    val retention30d: Double = 0.0,
    val totalWords: Int = 0,
    val matureWords: Int = 0,
    val leechWords: Int = 0,
    /** Lượt trả lời thẻ đã học hôm nay (trừ vào hạn mức ôn tối đa). */
    val reviewsDoneToday: Int = 0,
    /** Thẻ mới đã được giới thiệu hôm nay (trừ vào hạn mức thẻ mới mỗi ngày). */
    val newIntroducedToday: Int = 0,
    val streakShields: Int = 0
) {
    val goalProgress: Float
        get() = if (dailyGoal <= 0) 0f else (reviewedToday.toFloat() / dailyGoal).coerceIn(0f, 1f)
    val isGoalReached: Boolean get() = dailyGoal > 0 && reviewedToday >= dailyGoal
}

/** Đọc trạng thái FSRS ra khỏi bản ghi Room. */
fun SavedWord.toMemoryState(): MemoryState = MemoryState(
    stability = stability,
    difficulty = difficulty,
    state = CardState.from(fsrsState),
    reps = reps,
    lapses = lapses,
    lastReviewEpoch = lastReviewEpoch,
    dueEpoch = dueEpoch
)

/** Bản sao của thẻ sau khi áp trạng thái FSRS mới — để giao diện có thẻ mới nhất mà không phải đọc lại DB. */
fun SavedWord.withMemory(state: MemoryState, now: Long): SavedWord = copy(
    stability = state.stability,
    difficulty = state.difficulty,
    fsrsState = state.state.value,
    reps = state.reps,
    lapses = state.lapses,
    lastReviewEpoch = state.lastReviewEpoch,
    dueEpoch = state.dueEpoch,
    nextReviewEpoch = state.dueEpoch,
    masteredCount = if (state.stability >= 21.0) 1 else 0,
    srsStage = legacyStageFor(state),
    updatedAt = now,
    isSynced = false
)

/** Giai đoạn theo thang cũ 0..4 — chỉ giữ để tương thích với dữ liệu và bộ lọc cũ. */
fun legacyStageFor(state: MemoryState): Int = when {
    state.state == CardState.NEW -> 0
    state.stability >= 21.0 -> 4
    state.stability >= 7.0 -> 3
    state.stability >= 2.0 -> 2
    else -> 1
}
