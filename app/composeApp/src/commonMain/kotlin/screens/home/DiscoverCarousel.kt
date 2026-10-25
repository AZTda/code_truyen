package com.truyencloud.compose.screens.home

import com.truyencloud.compose.components.FeaturedItem
import com.truyencloud.shared.library.DiscoverFeed
import com.truyencloud.shared.library.DiscoverPolicy
import com.truyencloud.shared.library.DiscoverSort
import com.truyencloud.shared.library.LibraryListLogic

/**
 * Hàng nổi bật đầu tab Khám phá: [DiscoverPolicy.CAROUSEL_SIZE] truyện đầu của CHÍNH bảng đang chọn, huy hiệu ghi đúng bảng
 * đó ([DiscoverPresentation]). Trước đây hàng này luôn lấy từ "Đọc nhiều" nên mọi tài khoản thấy cùng năm truyện.
 */
internal object DiscoverCarousel {

    fun items(feed: DiscoverFeed, selected: DiscoverSort, libraryKeys: Set<String>): List<FeaturedItem> {
        val shown = DiscoverPresentation.shownSort(selected, feed)
        val badge = DiscoverPresentation.badge(shown)
        return feed.novels.take(DiscoverPolicy.CAROUSEL_SIZE).map { novel ->
            FeaturedItem(
                novel = novel,
                tag = badge,
                subtitle = DiscoverPresentation.subtitle(shown, feed.reasonOf(novel)),
                inLibrary = LibraryListLogic.isInLibrary(novel, libraryKeys)
            )
        }
    }
}
