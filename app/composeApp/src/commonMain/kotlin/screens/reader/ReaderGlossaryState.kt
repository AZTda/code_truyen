package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.reader.GlossaryEntry

/** Trạng thái bảng thuật ngữ trong màn đọc: danh sách đã gộp (trên máy + máy chủ) và các thông báo. */
data class GlossaryUiState(
    val visible: Boolean = false,
    val entries: List<GlossaryEntry> = emptyList(),
    val isLoading: Boolean = false,
    /** Không tải được bảng của máy chủ: vẫn hiện bảng trên máy kèm câu này. */
    val serverNotice: String? = null,
    /** Kết quả lần thêm / sửa gần nhất (lỗi nhập liệu hoặc xác nhận). */
    val message: String? = null
)
