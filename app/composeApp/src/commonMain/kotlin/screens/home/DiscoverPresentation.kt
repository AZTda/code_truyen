package com.truyencloud.compose.screens.home

import com.truyencloud.shared.library.DiscoverFeed
import com.truyencloud.shared.library.DiscoverMode
import com.truyencloud.shared.library.DiscoverSort

/**
 * Chữ hiển thị của tab Khám phá theo bảng đang xem (thuần, không đụng Compose nên kiểm thử được).
 *
 * Điều cần nhớ: xin "Dành cho bạn" mà máy chủ trả bảng chung (tủ sách còn trống, máy chủ cũ...) thì nội dung thật là
 * "Mới cập nhật". Tiêu đề và huy hiệu phải nói đúng như vậy chứ không giả vờ đã cá nhân hoá ([DiscoverFeed.personalized]).
 */
internal object DiscoverPresentation {

    /** Bảng mà danh sách đang hiện thực sự thuộc về. */
    fun shownSort(selected: DiscoverSort, feed: DiscoverFeed): DiscoverSort =
        if (selected.isPersonal && !feed.personalized) DiscoverSort.RECENT else selected

    /** Nhãn nhỏ trên đầu thẻ nổi bật (viết hoa chữ đầu câu, không in hoa cả cụm). */
    fun badge(shown: DiscoverSort): String = when (shown) {
        DiscoverSort.FOR_YOU -> "Dành cho bạn"
        DiscoverSort.RECENT -> "Mới cập nhật"
        DiscoverSort.POPULAR -> "Đọc nhiều"
    }

    /**
     * Nhãn trên ô chọn bảng. Ba ô trên màn 360dp: bản [compact] (chữ hệ thống lớn) rút "Dành cho bạn" và "Mới cập nhật"
     * để vẫn nằm trọn một dòng.
     */
    fun segmentLabel(sort: DiscoverSort, compact: Boolean): String = when {
        !compact -> sort.label
        sort == DiscoverSort.FOR_YOU -> "Cho bạn"
        sort == DiscoverSort.RECENT -> "Cập nhật"
        else -> sort.label
    }

    /** Dòng phụ trên thẻ băng chuyền; truyện được gợi ý riêng thì ưu tiên lý do của máy chủ. */
    fun subtitle(shown: DiscoverSort, reason: String): String = when {
        reason.isNotBlank() -> reason
        shown == DiscoverSort.FOR_YOU -> "Chọn riêng theo tủ sách của bạn"
        shown == DiscoverSort.POPULAR -> "Được nhiều độc giả theo dõi"
        else -> "Vừa có chương mới trên máy chủ"
    }

    /** Tiêu đề phần danh sách kèm số lượng truyện đang hiển thị. */
    fun sectionTitle(mode: DiscoverMode, shown: DiscoverSort, category: String, count: Int = 0): String {
        val base = when {
            category != DiscoverFallbackCatalog.ALL -> category
            mode == DiscoverMode.FALLBACK -> "Tác phẩm đặc sắc"
            shown == DiscoverSort.FOR_YOU -> "Gợi ý riêng cho bạn"
            shown == DiscoverSort.POPULAR -> "Được đọc nhiều nhất"
            else -> "Mới cập nhật trên máy chủ"
        }
        return if (count > 0) "$base ($count)" else base
    }

    /** Lời giải thích khi đã xin "Dành cho bạn" mà chỉ nhận được bảng chung; null = không cần. */
    fun hint(selected: DiscoverSort, feed: DiscoverFeed, mode: DiscoverMode): String? =
        if (mode == DiscoverMode.LIVE && shownSort(selected, feed) != selected) {
            "Chưa có đủ dữ liệu để gợi ý riêng cho bạn. Hãy thêm vài truyện vào tủ sách; tạm thời đang hiện truyện mới cập nhật."
        } else {
            null
        }
}
