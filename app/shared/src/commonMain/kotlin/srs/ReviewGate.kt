package com.truyencloud.shared.srs

/**
 * Một lượt trả lời có được phép ĐỔI LỊCH FSRS hay không.
 *
 * Trước đây mọi bài luyện tập (điền khuyết, gõ lại, nghe chép, quiz...) đều gọi thẳng FSRS với điểm
 * tự động. Người học luyện lại một từ vừa ôn xong, hoặc luyện một từ chưa đến hạn, sẽ làm khoảng
 * cách ôn phình ra ngoài ý muốn — và dễ "chơi" lịch bằng cách gõ lại đúng chữ đang hiện trên màn hình.
 */
enum class ReviewPolicy {
    /** Luôn ghi lịch: phiên Thẻ SRS (thẻ đến hạn, thẻ mới được giới thiệu, bước học lại trong phiên). */
    SCHEDULE,

    /** Chỉ ghi lịch khi thẻ thực sự đến hạn (và hôm nay chưa ôn); ngược lại chỉ ghi nhật ký luyện tập. */
    SCHEDULE_IF_DUE,

    /** Không bao giờ đổi lịch: ôn nhồi, luyện phát âm. */
    PRACTICE_ONLY
}

object ReviewGate {

    /**
     * @param startOfStudyDay đầu ngày học hiện tại (xem [StudyDay.startOfToday])
     */
    fun shouldSchedule(
        state: MemoryState,
        policy: ReviewPolicy,
        nowEpoch: Long,
        startOfStudyDay: Long
    ): Boolean = when (policy) {
        ReviewPolicy.SCHEDULE -> true
        ReviewPolicy.PRACTICE_ONLY -> false
        ReviewPolicy.SCHEDULE_IF_DUE -> isDueForScheduling(state, nowEpoch, startOfStudyDay)
    }

    /**
     * Thẻ chưa từng học KHÔNG "đến hạn" — chỉ phiên Thẻ SRS mới được giới thiệu thẻ mới (theo hạn mức ngày).
     * Thẻ đang ở bước học ngắn (5–10 phút) đến hạn là được ghi ngay; thẻ Ôn tập phải đến hạn và chưa ôn hôm nay.
     */
    fun isDueForScheduling(state: MemoryState, nowEpoch: Long, startOfStudyDay: Long): Boolean {
        if (state.state == CardState.NEW || state.reps <= 0) return false
        if (state.dueEpoch <= 0L || state.dueEpoch > nowEpoch) return false
        return when (state.state) {
            CardState.LEARNING, CardState.RELEARNING -> true
            CardState.REVIEW -> state.lastReviewEpoch < startOfStudyDay
            CardState.NEW -> false
        }
    }
}
