package com.truyencloud.shared.repo

import com.truyencloud.shared.data.Chapter

/**
 * So khớp mục lục từ máy chủ với mục lục đã lưu, để làm mới mục lục chỉ GHI những gì thật
 * sự đổi. Bản cũ REPLACE lại mọi hàng ở mỗi lần mở trang truyện: tốn hàng nghìn lần ghi và
 * chép đè bản dịch / vị trí cuộn vừa lưu trong lúc chờ mạng.
 */
object ChapterToc {
    /** Tên/đường dẫn nào khác so với bản đã lưu thì cần cập nhật (bỏ qua giá trị rỗng của máy chủ). */
    fun needsMetadataUpdate(local: Chapter, remote: Chapter): Boolean =
        (remote.title.isNotBlank() && remote.title != local.title) ||
            (remote.titleVi.isNotBlank() && remote.titleVi != local.titleVi) ||
            (remote.url.isNotBlank() && remote.url != local.url)

    /** Metadata sau hợp nhất: lấy của máy chủ, trường nào máy chủ để trống thì giữ bản đã lưu. */
    fun mergedMetadata(local: Chapter, remote: Chapter): Triple<String, String, String> = Triple(
        remote.title.ifBlank { local.title },
        remote.titleVi.ifBlank { local.titleVi },
        remote.url.ifBlank { local.url }
    )

    /** Tách các chương ĐÃ CÓ cần sửa metadata khỏi các chương MỚI cần chèn. */
    fun diff(existing: Map<Int, Chapter>, remote: List<Chapter>): TocDiff {
        val toInsert = ArrayList<Chapter>()
        val toUpdate = ArrayList<Pair<Chapter, Chapter>>()
        for (r in remote) {
            val local = existing[r.chapterNumber]
            when {
                local == null -> toInsert.add(r)
                needsMetadataUpdate(local, r) -> toUpdate.add(local to r)
            }
        }
        return TocDiff(toInsert, toUpdate)
    }
}

class TocDiff(
    val toInsert: List<Chapter>,
    /** Cặp (bản đã lưu, bản máy chủ). */
    val toUpdate: List<Pair<Chapter, Chapter>>
) {
    val isEmpty: Boolean get() = toInsert.isEmpty() && toUpdate.isEmpty()
}
