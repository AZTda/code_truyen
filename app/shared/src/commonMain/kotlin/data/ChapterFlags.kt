package com.truyencloud.shared.data

import androidx.room.Ignore

/** Tên engine của bản dịch TẠM do Google dự phòng (không phải AI) — máy chủ sẽ thử AI lại sau. */
const val ENGINE_GOOGLE_FALLBACK = "GOOGLE_FALLBACK"

/**
 * Cờ trạng thái nội dung của một chương, tính thẳng bằng SQL nên KHÔNG kéo nguyên văn
 * hàng trăm chương lên bộ nhớ chỉ để biết chương nào đã có bản dịch.
 *
 * Danh sách chương "nhẹ" ([ChapterDao.getChapterListLight]) để trống cột nội dung, vì vậy
 * màn hình nào cần hiện huy hiệu "đã dịch" / "chỉ bản gốc" phải dùng lớp này thay vì đọc
 * `translatedContent` của từng phần tử trong danh sách.
 */
data class ChapterFlags(
    val chapterNumber: Int,
    /** Có văn bản gốc (thường là tiếng Trung). */
    val hasOriginal: Boolean,
    /** Có bản dịch tiếng Việt không rỗng. */
    val hasTranslation: Boolean,
    /** Bản dịch tiếng Việt là bản tạm của Google, chưa phải bản AI. */
    val isProvisional: Boolean,
    val hasEnglish: Boolean,
    val hasBilingual: Boolean
) {
    /** Sẵn sàng đọc offline ở chế độ tiếng Việt: có bản dịch tiếng Việt. */
    @get:Ignore
    val isOfflineReady: Boolean get() = hasTranslation

    /**
     * Chỉ có văn bản gốc (không có bản dịch Việt/Anh/song ngữ nào): mất mạng thì hiện
     * nguyên tiếng Trung — UI nên gắn nhãn "chỉ bản gốc".
     */
    @get:Ignore
    val isOriginalOnly: Boolean get() = hasOriginal && !hasTranslation && !hasEnglish && !hasBilingual
}
