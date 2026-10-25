package com.truyencloud.shared.library

import com.truyencloud.shared.data.Novel

/** Một bảng của tab Khám phá (dành cho bạn / mới cập nhật / đọc nhiều) và tình trạng tải của nó. */
data class DiscoverFeed(
    val novels: List<Novel> = emptyList(),
    /** Đang tải lần đầu và chưa có gì để hiện: hiện khung xương. */
    val isLoading: Boolean = false,
    /** Câu thân thiện mô tả lần tải gần nhất thất bại; [novels] vẫn là bản lưu cũ nếu có. */
    val error: String? = null,
    /** Mốc (epoch ms) của dữ liệu đang hiển thị; 0 = chưa từng có. */
    val savedAt: Long = 0L,
    /** Đang hiện bản lưu trên máy, chưa được máy chủ xác nhận trong phiên này. */
    val fromCache: Boolean = false,
    /** Máy chủ đã trả lời ít nhất một lần trong phiên này (kể cả danh sách rỗng). */
    val loaded: Boolean = false,
    /** Lý do gợi ý theo id truyện (chỉ bảng cá nhân hoá, và chỉ mục có lý do). */
    val reasons: Map<String, String> = emptyMap(),
    /** Danh sách đã được máy chủ xếp riêng cho tài khoản; false = bảng chung (kể cả khi đã xin "Dành cho bạn"). */
    val personalized: Boolean = false,
    /** Số chương đã dịch AI trên máy chủ theo id truyện. */
    val translatedCounts: Map<String, Int> = emptyMap(),
    /** Tập các id truyện đang bật Dịch Ngầm trên máy chủ. */
    val overnightIds: Set<String> = emptySet(),
    /** Thể loại tự động phân loại theo id truyện. */
    val categories: Map<String, String> = emptyMap()
) {
    fun reasonOf(novel: Novel): String = reasons[novel.id].orEmpty()
    fun translatedOf(novel: Novel): Int = translatedCounts[novel.id] ?: 0
    fun isOvernight(novel: Novel): Boolean = novel.id in overnightIds
    fun categoryOf(novel: Novel): String =
        categories[novel.id]?.takeIf { it.isNotBlank() } ?: DiscoverCategoryClassifier.classify(novel)

    /** [novels] kèm lý do gợi ý và thống kê dịch. */
    val entries: List<DiscoverEntry>
        get() = novels.map {
            DiscoverEntry(
                novel = it,
                reason = reasonOf(it),
                translatedChapters = translatedOf(it),
                overnightEnabled = isOvernight(it),
                category = categoryOf(it)
            )
        }

    /**
     * Chỉ khi máy chủ thật sự xếp riêng cho tài khoản: bảng chung không được gọi là "gợi ý cho bạn" (đưa vào
     * [SearchSuggestionBuilder] chẳng hạn).
     */
    val personalEntries: List<DiscoverEntry> get() = if (personalized) entries else emptyList()
}

/** Cách tab Khám phá vẽ một bảng xếp hạng. */
enum class DiscoverMode {
    /** Có danh sách từ máy chủ (hoặc bản lưu của lần trước). */
    LIVE,

    /** Chưa có gì để hiện và đang tải (hoặc chưa có lần trả lời nào): khung xương. */
    LOADING,

    /** Không tải được / máy chủ chưa có truyện công khai: dùng tuyển chọn có sẵn. */
    FALLBACK
}

/**
 * Máy trạng thái thuần của một [DiscoverFeed]: tách khỏi ViewModel / giao diện để kiểm thử được các tình huống
 * lần đầu mở, mất mạng, máy chủ rỗng và làm mới khi đã có bản cũ.
 */
object DiscoverFeeds {

    fun mode(feed: DiscoverFeed): DiscoverMode = when {
        feed.novels.isNotEmpty() -> DiscoverMode.LIVE
        // Chưa có lần trả lời nào (kể cả lỗi) thì đừng vội hiện tuyển chọn có sẵn
        feed.isLoading || (!feed.loaded && feed.error == null) -> DiscoverMode.LOADING
        else -> DiscoverMode.FALLBACK
    }

    /** Bản lưu bền của lần trước (đã giải mã) thành trạng thái ban đầu; không có thì để trống. */
    fun fromSnapshot(snapshot: DiscoverSnapshot?): DiscoverFeed =
        if (snapshot == null) {
            DiscoverFeed()
        } else {
            DiscoverFeed(
                novels = snapshot.novels,
                savedAt = snapshot.savedAt,
                fromCache = true,
                reasons = snapshot.reasons,
                personalized = snapshot.personalized
            )
        }

    /** Bắt đầu tải: chỉ hiện khung xương khi chưa có gì để hiện; có bản cũ thì giữ nguyên danh sách. */
    fun begin(feed: DiscoverFeed): DiscoverFeed = feed.copy(isLoading = feed.novels.isEmpty(), error = null)

    /** Máy chủ trả lời: thay thế hoàn toàn (kể cả khi rỗng, để không hiện lại danh sách đã lỗi thời). */
    fun succeeded(novels: List<Novel>, now: Long): DiscoverFeed =
        DiscoverFeed(novels = novels, savedAt = now, loaded = true)

    /** Như trên nhưng giữ lý do gợi ý và cờ cá nhân hoá của [result]. */
    fun succeeded(result: DiscoverResult, now: Long): DiscoverFeed = DiscoverFeed(
        novels = result.novels,
        savedAt = now,
        loaded = true,
        reasons = result.entries.reasonsById(),
        personalized = result.personalized,
        translatedCounts = result.entries.translatedById(),
        overnightIds = result.entries.overnightIds(),
        categories = result.entries.categoriesById()
    )

    /** Tải lỗi: giữ nguyên danh sách cũ (nếu có), chỉ ghi câu báo lỗi thân thiện. */
    fun failed(old: DiscoverFeed, message: String): DiscoverFeed = old.copy(isLoading = false, error = message)

    /** Mọi bảng xếp hạng đều có dữ liệu còn "tươi" thì mở lại tab không cần gọi mạng. */
    fun isFresh(feeds: Collection<DiscoverFeed>, now: Long): Boolean =
        feeds.all { it.novels.isNotEmpty() && DiscoverPolicy.isFresh(it.savedAt, now) }

    /** Dải báo phía trên danh sách khi đang hiện bản lưu / tuyển chọn có sẵn; null = không cần báo. */
    fun notice(feed: DiscoverFeed): String? {
        val error = feed.error
        return when (mode(feed)) {
            DiscoverMode.FALLBACK ->
                if (error != null) "$error Đang hiển thị tuyển chọn có sẵn."
                else "Máy chủ chưa có truyện công khai nào. Đang hiển thị tuyển chọn có sẵn."
            DiscoverMode.LIVE -> if (error != null) "$error Đang hiển thị danh sách đã lưu." else null
            DiscoverMode.LOADING -> null
        }
    }
}
