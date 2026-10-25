package com.truyencloud.shared.repo

import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.srs.CardState
import com.truyencloud.shared.srs.FsrsScheduler

/**
 * Dựng hàng đợi học trong ngày.
 *
 * Thứ tự: (1) thẻ ĐÃ HỌC đến hạn — quá hạn nhiều / sắp quên nhất trước — tối đa
 * `maxReviewsPerDay - đã ôn hôm nay`; rồi (2) thẻ MỚI, tối đa `newCardsPerDay - thẻ mới đã giới thiệu hôm nay`.
 *
 * Bản cũ coi thẻ mới luôn "đến hạn" (dueEpoch = lúc lưu) và chấm cho chúng điểm ưu tiên cao gấp 16 lần
 * thẻ ôn, nên lưu 40 từ trong một buổi đọc là 40 thẻ mới chen lên đầu, đẩy thẻ sắp quên ra khỏi hàng đợi.
 */
object StudyQueueBuilder {

    fun build(
        dueWords: List<SavedWord>,
        newWords: List<SavedWord>,
        reviewsDoneToday: Int,
        newIntroducedToday: Int,
        nowEpoch: Long,
        config: StudyConfig,
        activeBookTitle: String? = null
    ): List<SavedWord> {
        val reviewSlots = (config.maxReviewsPerDay - reviewsDoneToday).coerceAtLeast(0)
        val newSlots = (config.newCardsPerDay - newIntroducedToday).coerceAtLeast(0)

        val reviews = dueWords
            .filter { !it.isSuspended && !it.isDeleted && !it.isNeverStudied }
            .sortedByDescending {
                FsrsScheduler.queuePriority(it.toMemoryState(), nowEpoch, it.bookTitle, activeBookTitle)
            }
            .take(reviewSlots)

        val taken = reviews.mapTo(HashSet()) { it.id }
        val fresh = newWords
            .filter { !it.isSuspended && !it.isDeleted && it.isNeverStudied && it.id !in taken }
            // Từ của cuốn đang đọc dở trước, rồi từ lưu sớm nhất trước.
            .sortedWith(
                compareByDescending<SavedWord> {
                    !activeBookTitle.isNullOrBlank() && it.bookTitle.equals(activeBookTitle, ignoreCase = true)
                }.thenBy { it.createdAt }
            )
            .take(newSlots)

        return reviews + fresh
    }

    /** Số thẻ còn lại phải học hôm nay = độ dài hàng đợi [build] — dùng cho mục tiêu ngày. */
    fun remaining(
        dueCount: Int,
        newAvailable: Int,
        reviewsDoneToday: Int,
        newIntroducedToday: Int,
        config: StudyConfig
    ): Int {
        val reviewSlots = (config.maxReviewsPerDay - reviewsDoneToday).coerceAtLeast(0)
        val newSlots = (config.newCardsPerDay - newIntroducedToday).coerceAtLeast(0)
        return minOf(dueCount, reviewSlots) + minOf(newAvailable, newSlots)
    }

    /** Thẻ ở trạng thái học ngắn (Again/Hard trong ngày) — cho phép phiên ưu tiên chúng. */
    fun isStepCard(word: SavedWord): Boolean {
        val state = CardState.from(word.fsrsState)
        return state == CardState.LEARNING || state == CardState.RELEARNING
    }
}
