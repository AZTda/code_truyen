package com.truyencloud.shared.repo

import kotlin.math.abs

/** Tên kệ đặc biệt dùng làm "bia mộ": truyện đã bị xoá khỏi tủ sách (máy chủ không có API xoá tiến độ). */
const val SHELF_REMOVED = "removed"

/** Ảnh chụp tiến độ đọc của một truyện (bản cục bộ hoặc bản trên máy chủ). */
data class ProgressSnapshot(
    /** Giờ sửa (epoch ms). 0 = chưa rõ (dữ liệu rất cũ). */
    val stamp: Long,
    val chapter: Int,
    val scroll: Float
)

enum class ProgressWinner { LOCAL, REMOTE, SAME }

/** Việc cần làm với một dòng tiến độ nhận từ máy chủ. */
enum class RemoteRowAction {
    /** Bỏ qua (không có gì để áp, hoặc không đủ dữ liệu dựng truyện). */
    SKIP,

    /** Máy này chưa có truyện: dựng hàng mới từ metadata kèm theo. */
    INSERT_LOCAL,

    /** Bản máy chủ mới hơn: áp vào hàng cục bộ. */
    APPLY_REMOTE,

    /** Máy chủ ghi truyện đã bị xoá khỏi tủ (mới hơn bản cục bộ): xoá hàng cục bộ. */
    REMOVE_LOCAL,

    /** Bản cục bộ mới hơn hoặc bằng: giữ nguyên (nếu còn dirty sẽ được đẩy lên). */
    KEEP_LOCAL
}

/** Dòng tiến độ từ máy chủ, rút gọn cho việc quyết định. */
data class RemoteRow(
    val snapshot: ProgressSnapshot,
    val shelf: String,
    val hasNovelPayload: Boolean
) {
    val isRemoved: Boolean get() = shelf == SHELF_REMOVED
}

object ProgressConflict {
    private const val SCROLL_EPSILON = 0.001f

    /**
     * Ai thắng khi hai máy cùng có tiến độ cho một truyện.
     *
     * So theo THỨ TỰ: giờ sửa → chương → vị trí cuộn. Bản cũ bỏ qua bản máy chủ hễ hai bên
     * cùng chương, nên một chương dài đọc dở ở máy kia luôn quay về vị trí cũ ở máy này.
     * Giờ sửa đứng đầu (không phải "chương lớn hơn thì thắng"): người đọc lại từ đầu trên
     * máy này không bị kéo ngược về chương cuối của máy kia.
     */
    fun resolve(local: ProgressSnapshot?, remote: ProgressSnapshot): ProgressWinner {
        if (local == null) return ProgressWinner.REMOTE
        if (remote.stamp != local.stamp) {
            return if (remote.stamp > local.stamp) ProgressWinner.REMOTE else ProgressWinner.LOCAL
        }
        if (remote.chapter != local.chapter) {
            return if (remote.chapter > local.chapter) ProgressWinner.REMOTE else ProgressWinner.LOCAL
        }
        if (abs(remote.scroll - local.scroll) < SCROLL_EPSILON) return ProgressWinner.SAME
        return if (remote.scroll > local.scroll) ProgressWinner.REMOTE else ProgressWinner.LOCAL
    }

    /**
     * Quyết định việc làm với một dòng từ máy chủ.
     *
     * @param local bản cục bộ, null nếu truyện không có trong tủ sách của máy này.
     * @param removedAt giờ xoá cục bộ còn đang chờ báo máy chủ, null nếu không có.
     */
    fun decideRemoteRow(remote: RemoteRow, local: ProgressSnapshot?, removedAt: Long?): RemoteRowAction {
        if (local == null) {
            if (remote.isRemoved || !remote.hasNovelPayload) return RemoteRowAction.SKIP
            // Đã xoá ở máy này SAU lần sửa cuối của máy chủ: đừng để nó sống lại.
            if (removedAt != null && removedAt >= remote.snapshot.stamp) return RemoteRowAction.SKIP
            return RemoteRowAction.INSERT_LOCAL
        }
        if (remote.isRemoved) {
            return if (remote.snapshot.stamp >= local.stamp) RemoteRowAction.REMOVE_LOCAL
            else RemoteRowAction.KEEP_LOCAL
        }
        return when (resolve(local, remote.snapshot)) {
            ProgressWinner.REMOTE -> RemoteRowAction.APPLY_REMOTE
            ProgressWinner.LOCAL, ProgressWinner.SAME -> RemoteRowAction.KEEP_LOCAL
        }
    }
}
