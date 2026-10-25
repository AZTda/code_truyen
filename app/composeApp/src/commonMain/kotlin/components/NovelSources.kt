package com.truyencloud.compose.components

/** Nguồn truyện hiển thị cho người đọc: tên thân thiện thay cho mã nguồn kỹ thuật ("jjwxc", "69shuba"...). */
object NovelSources {
    /** Các nguồn lọc trong tìm kiếm, "Tất cả" đứng đầu. */
    val filters: List<SourceItem> = listOf(
        SourceItem("all", "Tất cả"),
        SourceItem("69shuba", "69 Thư Ba"),
        SourceItem("piaotia", "Phiêu Thiên"),
        SourceItem("uukanshu", "UU Đọc Sách")
    )

    /** Tên hiển thị của một nguồn; null nếu nguồn trống. Truyện trong kho riêng ghi "Có sẵn" / "Tệp riêng". */
    fun label(sourceId: String): String? = when {
        sourceId.isBlank() -> null
        sourceId == "local" -> "Có sẵn"
        sourceId == "local_import" -> "Tệp riêng"
        else -> filters.firstOrNull { it.id == sourceId }?.name ?: sourceId
    }
}
