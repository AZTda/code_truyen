package com.truyencloud.shared.repo

import com.truyencloud.shared.data.ReviewLog
import com.truyencloud.shared.data.ReviewLogKey
import com.truyencloud.shared.data.SavedWord

/**
 * Luật gộp dữ liệu từ vựng giữa máy và máy chủ (hợp đồng C7) — hàm thuần để kiểm thử được.
 *
 *  - Mỗi từ: bản có mốc `max(updatedAt, deletedAt)` LỚN hơn thắng; bằng nhau thì bia mộ thắng
 *    (giống `incoming_wins` phía máy chủ, nên hai bên luôn cùng kết luận).
 *  - Log ôn tập bất biến, khoá tự nhiên là (wordId, reviewedAt) — `id` tự tăng của máy khác bị bỏ.
 */
object VocabularySyncMerge {

    fun stamp(word: SavedWord): Long = maxOf(word.updatedAt, word.deletedAt)

    /** true nếu bản từ máy chủ phải thay thế bản cục bộ. */
    fun remoteWins(local: SavedWord?, remote: SavedWord): Boolean {
        // Chưa từng có từ này: nhận bản sống; bia mộ của từ chưa từng có thì không cần lưu.
        if (local == null) return !remote.isDeleted
        val l = stamp(local)
        val r = stamp(remote)
        if (r != l) return r > l
        return remote.isDeleted && !local.isDeleted
    }

    /**
     * Khi hai dòng khác `id` nhưng cùng `word` (chỉ mục unique trên `word`): giữ dòng MỚI hơn.
     * @return true nếu nên giữ bản từ máy chủ (và bỏ dòng cục bộ).
     */
    fun remoteBeatsClash(clash: SavedWord, remote: SavedWord): Boolean = stamp(remote) > stamp(clash)

    /** Lọc log kéo về: bỏ log đã có (theo khoá tự nhiên), bỏ log thiếu khoá, bỏ trùng trong chính lô. `id` về 0. */
    fun newLogs(remote: List<ReviewLog>, existing: Set<ReviewLogKey>): List<ReviewLog> {
        val seen = existing.toHashSet()
        val fresh = ArrayList<ReviewLog>()
        for (log in remote) {
            if (log.wordId.isBlank() || log.reviewedAt <= 0L) continue
            if (seen.add(ReviewLogKey(log.wordId, log.reviewedAt))) {
                fresh += log.copy(id = 0L, isSynced = true)
            }
        }
        return fresh
    }
}
