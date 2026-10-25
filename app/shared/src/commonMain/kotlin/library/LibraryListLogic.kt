package com.truyencloud.shared.library

import com.truyencloud.shared.data.Novel

/**
 * Logic thuần của danh sách tủ sách (lọc kệ / yêu thích, sắp xếp, đếm, truyện "Đọc tiếp"), tách khỏi
 * ViewModel để kiểm thử không cần Room hay giao diện.
 */
object LibraryListLogic {

    /** Số chương chưa đọc = tổng chương - chương đang đọc; tổng chưa rõ (0) hoặc đọc vượt thì là 0. */
    fun unreadChapters(novel: Novel): Int = (novel.totalChapters - novel.lastReadChapter).coerceAtLeast(0)

    fun shelfOf(novel: Novel): String = LibraryShelves.normalize(novel.shelfName)

    /** Số truyện của TỪNG kệ (kệ trống vẫn có mặt với 0). */
    fun shelfCounts(all: List<Novel>): Map<String, Int> {
        val counts = LibraryShelves.all.associate { it.id to 0 }.toMutableMap()
        for (n in all) {
            val shelf = shelfOf(n)
            counts[shelf] = (counts[shelf] ?: 0) + 1
        }
        return counts
    }

    fun inShelf(all: List<Novel>, shelf: String): List<Novel> {
        val id = LibraryShelves.normalize(shelf)
        return all.filter { shelfOf(it) == id }
    }

    /** Số truyện yêu thích trong một kệ (để hiện trên chip lọc). */
    fun favoriteCount(all: List<Novel>, shelf: String): Int = inShelf(all, shelf).count { it.isFavorite }

    /** Danh sách hiển thị của một kệ: lọc yêu thích (nếu bật) rồi sắp xếp. */
    fun visible(all: List<Novel>, shelf: String, favoritesOnly: Boolean, sort: LibrarySort): List<Novel> {
        val inShelf = inShelf(all, shelf)
        return sort.sort(if (favoritesOnly) inShelf.filter { it.isFavorite } else inShelf)
    }

    /**
     * Truyện đang đọc dở, mới nhất trước. Truyện ở kệ "Hoàn thành" không được mời "Đọc tiếp".
     */
    fun continueReading(all: List<Novel>): List<Novel> =
        all.filter { it.lastReadChapter > 0 && shelfOf(it) != LibraryShelves.COMPLETED }
            .sortedWith(
                compareByDescending<Novel> { it.lastReadAt }
                    .thenByDescending { it.addedAt }
                    .thenBy { it.id }
            )

    // ── Thao tác hàng loạt trên các truyện đang chọn ──────────────────────────

    /** Truyện thật sự phải đổi kệ khi chuyển cả tập sang [shelf]; truyện đã ở kệ đó thì bỏ qua. */
    fun needingShelfMove(targets: List<Novel>, shelf: String): List<Novel> {
        val id = LibraryShelves.normalize(shelf)
        return targets.filter { shelfOf(it) != id }
    }

    /** Kết quả của nút "Yêu thích" cho một tập truyện: đặt hay bỏ yêu thích, và truyện nào cần đổi. */
    data class FavoriteChange(val makeFavorite: Boolean, val toChange: List<Novel>)

    /** Tất cả đã yêu thích thì bỏ yêu thích; còn lại (kể cả lẫn lộn) thì đặt yêu thích cho cả tập. */
    fun favoriteChange(targets: List<Novel>): FavoriteChange {
        val makeFavorite = targets.isNotEmpty() && !targets.all { it.isFavorite }
        return FavoriteChange(makeFavorite, targets.filter { it.isFavorite != makeFavorite })
    }

    // ── Nhận diện "đã có trong tủ" cho Khám phá / kết quả tìm kiếm ───────────

    /**
     * Khoá nhận diện truyện trong tủ: id VÀ địa chỉ nguồn. Máy chủ có thể trả id chuẩn khác id mà
     * ứng dụng từng dùng để mở truyện (xem NovelRepository.migrateNovelId) nhưng địa chỉ nguồn thì giống nhau.
     */
    fun libraryKeys(library: List<Novel>): Set<String> {
        val keys = HashSet<String>(library.size * 2)
        for (n in library) {
            keys += idKey(n.id)
            normalizeUrl(n.url)?.let { keys += urlKey(it) }
        }
        return keys
    }

    fun isInLibrary(novel: Novel, keys: Set<String>): Boolean {
        if (keys.isEmpty()) return false
        if (novel.id.isNotBlank() && idKey(novel.id) in keys) return true
        val url = normalizeUrl(novel.url) ?: return false
        return urlKey(url) in keys
    }

    private fun idKey(id: String) = "id:$id"
    private fun urlKey(url: String) = "url:$url"

    /** Bỏ giao thức, "www.", dấu "/" cuối và khác biệt hoa/thường để so hai địa chỉ nguồn. */
    internal fun normalizeUrl(raw: String): String? {
        var u = raw.trim().lowercase()
        if (u.isEmpty()) return null
        u = u.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        return u.ifEmpty { null }
    }
}
