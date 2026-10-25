package com.truyencloud.compose.screens.detail

import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.ChapterFlags
import com.truyencloud.shared.data.Novel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Scope mặc định của [NovelDetailViewModel]: SupervisorJob để một coroutine lỗi không kéo chết cả màn
 * hình, và có bộ bắt lỗi để lỗi không ai bắt (mạng chập chờn, 401...) không làm sập ứng dụng.
 */
internal fun detailViewModelScope(): CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> e.printStackTrace() }
)

/** Gộp cờ nội dung (theo số chương) cho giao diện. */
object ChapterFlagIndex {
    fun build(flags: List<ChapterFlags>): Map<Int, ChapterFlags> = flags.associateBy { it.chapterNumber }
}

/** Nhãn kệ hiển thị cho người dùng (khớp các tab kệ ở màn Tủ sách). */
fun shelfLabel(shelfName: String): String = when (shelfName) {
    "reading" -> "Đang đọc"
    "completed" -> "Hoàn thành"
    "planned" -> "Bộ sưu tập"
    else -> shelfName
}

/**
 * Truyện để hiển thị = metadata nguồn + (nếu ở trong tủ) tiến độ/kệ/yêu thích của hàng đã lưu.
 * Không ở trong tủ thì KHÔNG có tiến độ/kệ/yêu thích nào, kể cả giá trị cũ mang theo từ danh sách.
 */
internal fun composeDisplayNovel(source: Novel, stored: Novel?): Novel =
    if (stored != null) {
        source.copy(
            lastReadChapter = stored.lastReadChapter,
            shelfName = stored.shelfName,
            isFavorite = stored.isFavorite,
            lastReadAt = stored.lastReadAt,
            addedAt = stored.addedAt,
            syncUpdatedAt = stored.syncUpdatedAt,
            syncDirty = stored.syncDirty
        )
    } else {
        source.copy(lastReadChapter = 0, shelfName = "reading", isFavorite = false, lastReadAt = 0L, addedAt = 0L)
    }

/**
 * Trạng thái sau khi nạp từ máy chủ thất bại. Chỉ hiện chế độ offline nếu SQLite của ĐÚNG truyện này đã
 * có chương; nếu đã có thông tin truyện (tiêu đề, giới thiệu) thì không che cả màn hình bằng màn hình lỗi.
 */
internal fun NovelDetailUiState.afterRemoteFailure(local: List<Chapter>, error: Exception): NovelDetailUiState = when {
    local.isNotEmpty() -> copy(chapters = local, isLoading = false, isOfflineMode = true, errorMessage = null)
    !novel?.title.isNullOrBlank() -> copy(chapters = emptyList(), isLoading = false, isOfflineMode = false, errorMessage = null)
    else -> copy(
        chapters = emptyList(),
        isLoading = false,
        isOfflineMode = false,
        errorMessage = error.message?.takeIf { it.isNotBlank() } ?: "Không thể kết nối máy chủ để tải danh sách chương."
    )
}
