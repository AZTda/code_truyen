package com.truyencloud.shared.repo

import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.ENGINE_GOOGLE_FALLBACK

/** Trạng thái tải offline của một chương ở chế độ tiếng Việt. */
enum class ChapterDownloadState {
    /** Có bản dịch dùng được (và là bản AI nếu đang đòi hỏi). Đọc offline trơn tru. */
    READY,

    /** Chỉ có bản dịch TẠM của Google trong khi người dùng đang đăng nhập — nên nâng lên bản AI. */
    PROVISIONAL,

    /** Chỉ có văn bản gốc: mất mạng sẽ hiện nguyên tiếng Trung. */
    ORIGINAL_ONLY,

    /** Chưa có nội dung nào. */
    MISSING
}

/**
 * Khi nào một chương được coi là "đã tải để đọc offline".
 *
 * Bản cũ coi chương chỉ có văn bản gốc là hoàn chỉnh. Bộ tải trước lưu N+1..N+5 ở dạng thô,
 * rồi tải hàng loạt BỎ QUA các chương ấy vì "đã có nội dung" — offline người đọc thấy
 * nguyên tiếng Trung dù giao diện báo đã tải.
 */
object ChapterOfflinePolicy {
    private val REFUSAL_MARKERS = listOf("The prompt could not be submitted", "violate Google")

    /** Bản dịch thực chất là câu từ chối của mô hình, không phải nội dung truyện. */
    fun isRefusalText(text: String?): Boolean =
        !text.isNullOrBlank() && REFUSAL_MARKERS.any { text.contains(it) }

    /** Có bản dịch tiếng Việt dùng được (không rỗng, không phải câu từ chối). */
    fun hasUsableTranslation(chapter: Chapter): Boolean {
        val t = chapter.translatedContent
        return !t.isNullOrBlank() && !isRefusalText(t)
    }

    /** Có bất kỳ nội dung hiển thị được khi mất mạng (kể cả chỉ bản gốc) — dùng cho đường lùi khi lỗi mạng. */
    fun hasAnyContent(chapter: Chapter): Boolean =
        !chapter.originalContent.isNullOrBlank() ||
            hasUsableTranslation(chapter) ||
            chapter.translatedContentEn.isNotBlank() ||
            chapter.translatedContentBilingual.isNotBlank()

    /**
     * Phân loại một chương đã lưu.
     *
     * @param requireAi true khi người dùng đang đăng nhập (máy chủ có thể nâng bản tạm lên
     * bản AI); khách không thể dịch AI nên bản tạm đã là tốt nhất có thể.
     */
    fun classify(chapter: Chapter?, requireAi: Boolean): ChapterDownloadState {
        if (chapter == null) return ChapterDownloadState.MISSING
        if (hasUsableTranslation(chapter)) {
            val provisional = chapter.translationEngine == ENGINE_GOOGLE_FALLBACK
            return if (requireAi && provisional) ChapterDownloadState.PROVISIONAL else ChapterDownloadState.READY
        }
        return if (!chapter.originalContent.isNullOrBlank()) ChapterDownloadState.ORIGINAL_ONLY
        else ChapterDownloadState.MISSING
    }

    /** Cần tải/dịch lại chương này để đạt trạng thái sẵn sàng offline không. */
    fun needsFetch(state: ChapterDownloadState): Boolean = state != ChapterDownloadState.READY
}
