package com.truyencloud.shared.repo

/** Việc cần làm khi hợp nhất một bookmark nhận từ máy chủ vào dữ liệu cục bộ. */
enum class BookmarkMergeAction {
    /** Ghi (chèn/thay) bản của máy chủ. */
    TAKE_REMOTE,

    /** Máy chủ ghi bookmark đã bị xoá (thắng): xoá hẳn hàng cục bộ. */
    DELETE_LOCAL,

    /** Giữ bản cục bộ (mới hơn, hoặc hai bên như nhau). */
    KEEP_LOCAL,

    /** Không có gì để làm (vd. bia mộ của thứ chưa từng có ở máy này). */
    IGNORE
}

/**
 * Hợp nhất bookmark theo quy tắc của máy chủ: last-write-wins theo giờ sửa, và khi bằng giờ
 * thì bia mộ (xoá) thắng. Giữ đúng quy tắc phía máy chủ (`sync_merge.incoming_wins`) để hai
 * bên không bao giờ "đánh nhau" — bên này kéo về rồi bên kia đẩy lại.
 *
 * Lớp thuần: nhận các con số chứ không nhận thực thể để kiểm thử gọn.
 */
object BookmarkMerge {
    /** Máy chủ chỉ trả bia mộ trong 90 ngày; cục bộ cũng dọn bia mộ đã xác nhận quá hạn này. */
    const val TOMBSTONE_TTL_MS: Long = 90L * 24 * 3600 * 1000

    /** Số bookmark tối đa mỗi yêu cầu đẩy lên (giới hạn của máy chủ). */
    const val MAX_PUSH_BATCH = 500

    /**
     * @param localStamp giờ sửa cục bộ (đã tính cả lúc xoá), null nếu máy này chưa có bookmark đó.
     * @param remoteStamp giờ sửa trên máy chủ = max(updated_at, deleted_at).
     */
    fun decide(
        localStamp: Long?,
        localDeleted: Boolean,
        remoteStamp: Long,
        remoteDeleted: Boolean
    ): BookmarkMergeAction {
        if (localStamp == null) {
            return if (remoteDeleted) BookmarkMergeAction.IGNORE else BookmarkMergeAction.TAKE_REMOTE
        }
        if (remoteStamp > localStamp) {
            return if (remoteDeleted) BookmarkMergeAction.DELETE_LOCAL else BookmarkMergeAction.TAKE_REMOTE
        }
        if (remoteStamp < localStamp) return BookmarkMergeAction.KEEP_LOCAL
        // Bằng giờ: bia mộ thắng. Cục bộ đã xoá mà máy chủ còn sống thì giữ bia mộ cục bộ.
        return if (remoteDeleted) BookmarkMergeAction.DELETE_LOCAL else BookmarkMergeAction.KEEP_LOCAL
    }

    /** Bia mộ cục bộ đã xác nhận với máy chủ và cũ hơn mốc này thì dọn được. */
    fun tombstoneCutoff(nowMs: Long): Long = nowMs - TOMBSTONE_TTL_MS

    /**
     * Con trỏ dùng cho lần kéo sau = mốc máy chủ trả về trừ một khoảng chồng lấn nhỏ.
     * Trễ vài giây còn hơn sót: hợp nhất là idempotent nên kéo lại dòng cũ không hại gì,
     * còn dòng ghi đúng mili-giây biên mà bị bỏ sót thì mất vĩnh viễn. Con trỏ bám theo đồng
     * hồ máy chủ (kể cả khi nó lùi) nên không bao giờ "vượt mặt" dữ liệu thật.
     */
    fun nextCursor(serverTimeMs: Long, previous: Long): Long =
        if (serverTimeMs <= 0L) previous else (serverTimeMs - CURSOR_OVERLAP_MS).coerceAtLeast(0L)

    const val CURSOR_OVERLAP_MS: Long = 2_000L
}
