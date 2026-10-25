package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ProgressNovelSummary
import com.truyencloud.shared.api.ProgressRemoteResponse
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.ChapterDao
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.NovelDao
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.SyncStateDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.data.parseIsoToEpochMillis
import com.truyencloud.shared.library.ServerNovelMetaStore

/**
 * Áp một dòng tiến độ nhận từ máy chủ vào Room theo [ProgressConflict]: dựng truyện mới từ metadata
 * kèm theo (máy mới dựng lại tủ sách), cập nhật tiến độ/kệ/yêu thích, hoặc gỡ truyện đã xoá ở máy khác.
 */
internal class ProgressRemoteApplier(
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val syncStateDao: SyncStateDao?
) {
    /** @return true nếu dữ liệu cục bộ đã thay đổi. */
    suspend fun apply(remote: ProgressRemoteResponse, fallback: Novel?): Boolean {
        val id = remote.novel_id
        if (id.isBlank()) return false
        recordServerMeta(remote)
        val local = novelDao.getNovelById(id)
        val stamp = remoteStamp(remote)
        val payload = remote.novel?.takeIf { it.title.isNotBlank() || it.title_vi.isNotBlank() }
        val row = RemoteRow(
            snapshot = ProgressSnapshot(stamp, remote.current_chapter, remote.scroll_position),
            shelf = remote.shelf_name,
            hasNovelPayload = payload != null || fallback != null
        )
        val localSnapshot = local?.let {
            ProgressSnapshot(it.localStamp, it.lastReadChapter, chapterDao.getScrollPosition(id, it.lastReadChapter) ?: 0f)
        }
        val removedAt = syncStateDao?.getLong(SyncKeys.libraryRemoved(id))

        return when (ProgressConflict.decideRemoteRow(row, localSnapshot, removedAt)) {
            RemoteRowAction.SKIP, RemoteRowAction.KEEP_LOCAL -> false
            RemoteRowAction.REMOVE_LOCAL -> {
                novelDao.deleteNovelById(id)
                true
            }
            RemoteRowAction.APPLY_REMOTE -> {
                adopt(remote)
                true
            }
            RemoteRowAction.INSERT_LOCAL -> {
                val base = payload?.toNovel(id) ?: fallback ?: return false
                val inserted = novelDao.insertIfAbsent(
                    base.copy(
                        id = id,
                        lastReadChapter = remote.current_chapter,
                        shelfName = remote.shelf_name,
                        isFavorite = remote.is_favorite,
                        lastReadAt = stamp,
                        addedAt = getCurrentEpochMillis(),
                        syncUpdatedAt = stamp,
                        syncDirty = false
                    )
                )
                if (inserted == -1L) return false
                syncStateDao?.delete(SyncKeys.libraryRemoved(id))
                storeRemoteScroll(id, remote.current_chapter, remote.scroll_position)
                true
            }
        }
    }

    /** Ghi đè tiến độ/kệ/yêu thích cục bộ bằng bản máy chủ (không đánh dấu dirty: đó chính là trạng thái máy chủ). */
    suspend fun adopt(remote: ProgressRemoteResponse) {
        recordServerMeta(remote)
        val stamp = remoteStamp(remote)
        novelDao.applyRemoteProgress(remote.novel_id, remote.current_chapter, stamp, remote.shelf_name, remote.is_favorite)
        storeRemoteScroll(remote.novel_id, remote.current_chapter, remote.scroll_position)
    }

    private fun recordServerMeta(remote: ProgressRemoteResponse) {
        val n = remote.novel ?: return
        if (n.translated_chapters > 0 || n.overnight_enabled || n.category.isNotBlank()) {
            ServerNovelMetaStore.record(remote.novel_id, n.translated_chapters, n.overnight_enabled, n.category)
        }
    }

    /**
     * Vị trí cuộn được lưu ở hàng CHƯƠNG (trình đọc đọc từ đó). Máy mới chưa có hàng chương thì
     * tạo hàng tối thiểu để mang vị trí sang: chương sẽ được điền nội dung khi mở.
     */
    private suspend fun storeRemoteScroll(novelId: String, chapter: Int, scroll: Float) {
        if (chapter <= 0 || scroll <= 0f) return
        val rows = chapterDao.updateReadingProgress(novelId, chapter, scroll, markRead = false)
        if (rows == 0) {
            chapterDao.insertChaptersIfAbsent(
                listOf(
                    Chapter(
                        id = "${novelId}_$chapter",
                        novelId = novelId,
                        chapterNumber = chapter,
                        title = "Chương $chapter",
                        scrollPosition = scroll
                    )
                )
            )
        }
    }

    private fun remoteStamp(remote: ProgressRemoteResponse): Long =
        remote.updated_at_ms.takeIf { it > 0 }
            ?: remote.last_read_at?.let { parseIsoToEpochMillis(it) }
            ?: 0L

    private fun ProgressNovelSummary.toNovel(id: String) = Novel(
        id = id,
        title = title.ifBlank { title_vi },
        titleVi = title_vi,
        author = author,
        url = source_url,
        coverUrl = cover_url,
        sourceId = source_id,
        totalChapters = total_chapters,
        description = description,
        status = status.ifBlank { "ongoing" }
    )
}
