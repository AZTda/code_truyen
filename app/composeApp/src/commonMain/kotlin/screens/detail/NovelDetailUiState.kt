package com.truyencloud.compose.screens.detail

import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.ChapterFlags
import com.truyencloud.shared.data.Novel

data class NovelDetailUiState(
    /**
     * Truyện để hiển thị: metadata nguồn mới nhất, cộng (nếu truyện đã ở trong tủ) tiến độ / kệ /
     * yêu thích lấy từ hàng đã lưu — nên "Đọc tiếp • Ch.X" luôn theo tiến độ thật, kể cả khi
     * vừa đọc xong ở trình đọc rồi bấm Back.
     */
    val novel: Novel? = null,
    /** Truyện có hàng trong tủ sách không. */
    val isInLibrary: Boolean = false,
    /** Đã biết chắc trạng thái tủ sách chưa (lần phát đầu tiên của Room). Chưa biết thì ẩn thanh tủ sách. */
    val libraryLoaded: Boolean = false,
    /** Mục lục KHÔNG kèm nội dung — huy hiệu đã dịch/chỉ bản gốc lấy từ [chapterFlags]. */
    val chapters: List<Chapter> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isOfflineMode: Boolean = false,
    val isFavorite: Boolean = false,
    /** Cờ nội dung từng chương theo số chương (có gốc / có bản dịch / bản tạm...). */
    val chapterFlags: Map<Int, ChapterFlags> = emptyMap(),
    /** Số chương đã có bản dịch AI trên ổ đĩa máy chủ (MiniPC). */
    val serverTranslatedCount: Int = 0,
    /** Danh sách số chương máy chủ đã dịch xong để đồng bộ nhanh về máy. */
    val serverTranslatedChapters: List<Int> = emptyList(),
    /** Cờ báo truyện đang được bật Dịch Ngầm Ban Đêm trên máy chủ. */
    val isOvernightEnabled: Boolean = false,
    val showBatchDownloadDialog: Boolean = false,
    val isBatchQueuing: Boolean = false,
    val batchMessage: String? = null,
    val batchProgress: Float? = null,
    /** Chưa đăng nhập: không dịch hàng loạt trên máy chủ được (chỉ tải bản có sẵn về máy). */
    val batchRequiresLogin: Boolean = false,
    val isCheckingUpdates: Boolean = false,
    val checkUpdateMessage: String? = null,
    /** Thong bao khi tien do doc duoc lay ve tu may chu (mo truyen tren may khac). */
    val restoredProgressMessage: String? = null,
    /** Thông báo ngắn về tủ sách ("Đã thêm vào tủ sách"...). */
    val libraryMessage: String? = null,
    val showRemoveConfirm: Boolean = false
) {
    /** Chương sẵn sàng đọc offline (đã có bản dịch). */
    val downloadedChapterNumbers: Set<Int>
        get() = chapterFlags.values.filter { it.isOfflineReady }.mapTo(HashSet()) { it.chapterNumber }
}
