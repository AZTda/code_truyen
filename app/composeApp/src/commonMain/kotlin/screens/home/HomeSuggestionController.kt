package com.truyencloud.compose.screens.home

import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.library.DiscoverSort
import com.truyencloud.shared.library.SearchHistoryStore
import com.truyencloud.shared.library.SearchSuggestionBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Gợi ý tìm kiếm CÁ NHÂN của lớp tìm kiếm: lịch sử tìm, tên truyện / tác giả trong tủ sách và bảng "Dành cho bạn" của chính
 * tài khoản đang dùng; danh sách mặc định chỉ còn là phần điền ([SearchSuggestionBuilder]). Kết quả nằm trong
 * [HomeUiState.suggestions] và được tính lại mỗi khi một trong ba nguồn đổi: tủ sách, bảng "Dành cho bạn" (hai nơi đó gọi
 * [refresh]), hoặc lịch sử (do chính lớp này ghi). Nguồn nào cũng thuộc về tài khoản hiện tại — lịch sử đọc theo chủ
 * ([SearchHistoryStore]), "Dành cho bạn" bị bỏ khi đổi tài khoản — nên [refresh] sau đổi tài khoản / xoá dữ liệu là đủ.
 * Bảng "Dành cho bạn" lấy thẳng từ [HomeUiState.discover]; khách và bảng chung (chưa cá nhân hoá) không có mục nào.
 *
 * Chạy trên luồng giao diện như [SearchHistoryStore].
 */
internal class HomeSuggestionController(
    private val history: SearchHistoryStore,
    /** Tủ sách hiện tại (Room). */
    private val library: () -> List<Novel>,
    private val state: MutableStateFlow<HomeUiState>
) {
    fun refresh() {
        val forYou = state.value.discover.feed(DiscoverSort.FOR_YOU).personalEntries
        val sections = SearchSuggestionBuilder.build(history.recent(), library(), forYou)
        state.update { if (it.suggestions == sections) it else it.copy(suggestions = sections) }
    }

    /** Ghi nhớ một lượt tìm có chủ đích (bấm Tìm, chạm gợi ý hoặc kết quả). Đường dẫn web / chuỗi rỗng bị bỏ qua. */
    fun record(query: String) {
        if (history.add(query)) refresh()
    }

    fun clearHistory() {
        history.clear()
        refresh()
    }
}
