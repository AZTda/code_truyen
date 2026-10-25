package com.truyencloud.compose.screens.settings

import com.truyencloud.shared.data.LocalDataMaintenance
import com.truyencloud.shared.repo.ChapterRepository

data class StorageSummary(val chapterCount: Int, val bytes: Long)

data class ClearResult(
    val chaptersCleared: Int,
    /** Dung lượng thật trả lại cho máy (đo tệp .db trước/sau VACUUM), hoặc ước lượng nếu không đo được. */
    val freedBytes: Long,
    /** VACUUM chạy được — tệp .db đã thực sự nhỏ lại. */
    val compacted: Boolean
) {
    fun message(scopeTitle: String? = null): String {
        val what = if (scopeTitle == null) "$chaptersCleared chương đã tải" else "$chaptersCleared chương của \"$scopeTitle\""
        return if (compacted) {
            "Đã xoá $what và giải phóng khoảng ${formatStorageBytes(freedBytes)}."
        } else {
            "Đã xoá $what (~${formatStorageBytes(freedBytes)}). Dung lượng trên đĩa sẽ được trả lại dần."
        }
    }
}

/**
 * Xoá nội dung chương đã tải (bản gốc + bản dịch VI/EN/song ngữ, KỂ CẢ bản dịch AI) rồi VACUUM để tệp .db nhỏ lại.
 * Giữ mục lục, tiến độ và vị trí cuộn. Dùng chung cho nút "Xoá…" ở Cài đặt và Quản lý bộ nhớ offline.
 */
class OfflineStorageCleaner(
    private val chapters: ChapterRepository,
    private val maintenance: LocalDataMaintenance?
) {
    suspend fun summary(): StorageSummary = StorageSummary(
        chapterCount = chapters.getCachedNovelSummaries().sumOf { it.cachedCount },
        bytes = chapters.estimateCacheBytes()
    )

    suspend fun clearAll(): ClearResult = clear { chapters.clearDownloadedContent() }

    suspend fun clearNovel(novelId: String): ClearResult = clear { chapters.clearContentForNovel(novelId) }

    private suspend fun clear(action: suspend () -> Int): ClearResult {
        val contentBefore = chapters.estimateCacheBytes()
        val fileBefore = maintenance?.databaseBytes() ?: 0L
        val cleared = action()
        val compacted = maintenance?.vacuum() == true
        val measured = (fileBefore - (maintenance?.databaseBytes() ?: 0L)).coerceAtLeast(0L)
        val estimated = (contentBefore - chapters.estimateCacheBytes()).coerceAtLeast(0L)
        return ClearResult(cleared, if (compacted && measured > 0L) measured else estimated, compacted)
    }
}
