package com.truyencloud.shared.tts

import com.truyencloud.shared.text.ParagraphSplitter

/**
 * Bản đọc (Việt / Anh / song ngữ / xen kẽ) đổi giữa chừng trong lúc đang nghe, mà bộ đọc vẫn giữ văn bản cũ: phải nạp lại
 * bản mới. Nếu không, bản đồ mảnh -> đoạn lệch: tô sáng nhảy về đầu chương và tiến độ nghe bị ghi đè ở đầu chương.
 */
object TtsSourceSync {
    /**
     * Đoạn cần đọc tiếp ở bản [new] khi bộ đọc (đang giữ bản [old], cùng chương) đang ở mảnh [speakerSegment]; null nếu
     * không cần nạp lại (nội dung không đổi, hoặc bản mới rỗng).
     */
    fun paragraphToResume(old: List<String>, new: List<String>, speakerSegment: Int): Int? {
        if (new.isEmpty() || new == old) return null
        val paragraph = ParagraphSplitter.paragraphIndexForSegment(ParagraphSplitter.buildTtsSegments(old), speakerSegment)
        return TtsProgressMath.equivalentParagraph(paragraph, old.size, new.size)
    }
}
