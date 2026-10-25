package com.truyencloud.compose.screens.home

import com.truyencloud.compose.components.FeaturedItem
import com.truyencloud.shared.data.Novel

/**
 * Tuyển chọn có sẵn, CHỈ dùng khi tab Khám phá không có gì khác để hiện (chưa từng tải được danh sách
 * từ máy chủ và không có bản lưu, hoặc máy chủ chưa có truyện công khai nào).
 */
internal object DiscoverFallbackCatalog {
    const val ALL = "Tất cả"

    data class Item(val novel: Novel, val category: String)

    val featured: List<FeaturedItem> = listOf(
        FeaturedItem(
            novel = piaotia("10_10527", "万古第一神", "Vạn Cổ Đệ Nhất Thần", "Phong Thanh Dương", "10/10527", "10527s.jpg", 7740),
            tag = "Tác phẩm đề cử",
            subtitle = "Thiên kiếm hùng tráng, trọn bộ"
        )
    )

    val items: List<Item> = listOf(
        Item(piaotia("10_10527", "万古第一神", "Vạn Cổ Đệ Nhất Thần", "Phong Thanh Dương", "10/10527", "10527s.jpg", 7740), "Huyền huyễn"),
        Item(piaotia("0_760", "凡人修仙传", "Phàm Nhân Tu Tiên", "Vong Ngữ", "0/760", "760s.jpg", 2446), "Tiên hiệp"),
        Item(piaotia("9_9459", "诡秘之主", "Quỷ Bí Chi Chủ", "Ái Tiềm Thủy Đích Ô Tặc", "9/9459", "9459s.jpg", 1432), "Huyền huyễn"),
        Item(piaotia("11_11014", "大奉打更人", "Đại Phụng Đả Canh Nhân", "Mại Báo Tiểu Lang Quân", "11/11014", "11014s.jpg", 812), "Lịch sử"),
        Item(piaotia("8_8717", "剑来", "Kiếm Lai", "Phong Hỏa Hí Chư Hầu", "8/8717", "8717s.jpg", 1200), "Tiên hiệp"),
        Item(piaotia("3_3155", "雪中悍刀行", "Tuyết Trung Hãn Đao Hành", "Phong Hỏa Hí Chư Hầu", "3/3155", "3155s.jpg", 990), "Kinh điển"),
        Item(piaotia("0_212", "庆余年", "Khánh Dư Niên", "Miêu Nị", "0/212", "212s.jpg", 770), "Lịch sử"),
        Item(piaotia("0_49", "斗罗大陆", "Đấu La Đại Lục", "Đường Gia Tam Thiếu", "0/49", "49s.jpg", 336), "Huyền huyễn")
    )

    /** "Tất cả" + chỉ những thể loại còn truyện (bản cũ có thể loại "Khoa Huyễn" luôn trống). */
    val categories: List<String> = listOf(ALL) + items.map { it.category }.distinct()

    fun novels(category: String): List<Novel> =
        items.filter { category == ALL || it.category == category }.map { it.novel }

    private fun piaotia(
        id: String,
        title: String,
        titleVi: String,
        author: String,
        path: String,
        coverFile: String,
        chapters: Int
    ) = Novel(
        id = id,
        title = title,
        titleVi = titleVi,
        author = author,
        url = "https://www.piaotia.com/html/$path/index.html",
        coverUrl = "https://www.piaotia.com/files/article/image/$path/$coverFile",
        sourceId = "piaotia",
        totalChapters = chapters
    )
}
