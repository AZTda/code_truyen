package com.truyencloud.shared.repo

/**
 * Chọn các chương cần tải trước, có chặn ở cuối truyện.
 *
 * Bản cũ luôn xin N+1..N+5 kể cả khi N đã là chương cuối, và máy chủ từng cào lại cả mục
 * lục nguồn cho MỖI chương không tồn tại ấy. Nay chặn theo chương cuối đã biết.
 */
object ChapterPrefetchPlanner {
    /** Số chương cuối biết được: lớn nhất trong mục lục cục bộ và tổng chương của truyện. */
    fun knownLast(localLast: Int, totalChapters: Int): Int = maxOf(localLast, totalChapters, 0)

    /**
     * @param current chương đang đọc.
     * @param ahead số chương muốn tải trước.
     * @param knownLast chương cuối đã biết (0 = chưa biết).
     * @param totalKnown true nếu [knownLast] đáng tin (có tổng chương của truyện, hoặc mục lục
     * cục bộ dài hơn chương hiện tại). Chưa biết gì thì chỉ dò đúng một chương kế tiếp: nếu đó
     * là chương không tồn tại máy chủ trả 404 ngay và [PrefetchEndCache] ghi nhớ điểm cuối.
     * @param knownEnd chương cuối thực sự do lần dò 404 trước đó phát hiện (null nếu chưa có).
     */
    fun targets(
        current: Int,
        ahead: Int,
        knownLast: Int,
        totalKnown: Boolean,
        knownEnd: Int? = null
    ): List<Int> {
        if (current <= 0 || ahead <= 0) return emptyList()
        var limit = if (totalKnown && knownLast > 0) knownLast else current + 1
        if (knownEnd != null) limit = minOf(limit, knownEnd)
        val last = minOf(current + ahead, limit)
        if (last <= current) return emptyList()
        return ((current + 1)..last).toList()
    }

    /** Chương có nên được dịch trước không: chưa có bản dịch, hoặc mới có bản tạm của Google. */
    fun shouldTranslateAhead(hasTranslation: Boolean, isProvisional: Boolean): Boolean =
        !hasTranslation || isProvisional
}

/** Ghi nhớ trong phiên chương cuối thực sự của từng truyện (phát hiện qua 404) để khỏi dò lại. */
class PrefetchEndCache {
    private val ends = HashMap<String, Int>()

    fun get(novelId: String): Int? = ends[novelId]

    /** Ghi nhận [missingChapter] không tồn tại, tức chương cuối là [missingChapter] - 1. */
    fun recordMissing(novelId: String, missingChapter: Int) {
        if (missingChapter <= 1) return
        val end = missingChapter - 1
        val old = ends[novelId]
        if (old == null || end < old) ends[novelId] = end
    }

    /** Mục lục vừa dài thêm (truyện có chương mới): quên điểm cuối cũ. */
    fun forget(novelId: String) {
        ends.remove(novelId)
    }
}
