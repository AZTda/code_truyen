package com.truyencloud.shared.library

import com.truyencloud.shared.api.DiscoverResponse
import com.truyencloud.shared.data.Novel

/**
 * Các bảng của tab Khám phá; [apiValue] là tham số `sort` của máy chủ. Thứ tự khai báo = thứ tự chip trên màn hình.
 *
 * [FOR_YOU] chỉ dành cho người đã đăng nhập: máy chủ xếp riêng theo tủ sách của chính họ, nên khách chỉ có
 * [RECENT] và [POPULAR] (xem [available]). Giá trị [RECENT] / [POPULAR] đã phát hành (1.1.x) — không được đổi.
 */
enum class DiscoverSort(val apiValue: String, val label: String) {
    FOR_YOU("for_you", "Dành cho bạn"),
    RECENT("recent", "Mới cập nhật"),
    POPULAR("popular", "Đọc nhiều");

    /** Bảng cá nhân hoá theo tài khoản: kết quả và bản lưu không được dùng chung giữa các tài khoản. */
    val isPersonal: Boolean get() = this == FOR_YOU

    companion object {
        /** Bảng mở sẵn: người đã đăng nhập vào "Dành cho bạn", khách vào "Mới cập nhật". */
        fun defaultFor(loggedIn: Boolean): DiscoverSort = if (loggedIn) FOR_YOU else RECENT

        /** Các chip hiển thị theo thứ tự; khách không có "Dành cho bạn". */
        fun available(loggedIn: Boolean): List<DiscoverSort> =
            if (loggedIn) DiscoverSort.entries.toList() else DiscoverSort.entries.filterNot { it.isPersonal }

        /**
         * Giữ lựa chọn nếu còn hợp lệ với trạng thái đăng nhập hiện tại, không thì về [defaultFor] — vd. vừa đăng xuất
         * khi đang đứng ở "Dành cho bạn".
         */
        fun coerce(selected: DiscoverSort, loggedIn: Boolean): DiscoverSort =
            if (selected in available(loggedIn)) selected else defaultFor(loggedIn)
    }
}

/**
 * Một truyện của Khám phá kèm lý do máy chủ gợi ý nó ("" = bảng chung, không có lý do). Lý do nằm ngoài [Novel]
 * vì [Novel] là thực thể Room: thêm trường vào đó là đổi lược đồ cơ sở dữ liệu.
 */
data class DiscoverEntry(
    val novel: Novel,
    val reason: String = "",
    val translatedChapters: Int = 0,
    val overnightEnabled: Boolean = false,
    val category: String = ""
)

/** id truyện → lý do gợi ý, chỉ các mục có lý do. */
fun List<DiscoverEntry>.reasonsById(): Map<String, String> =
    filter { it.reason.isNotBlank() }.associate { it.novel.id to it.reason }

fun List<DiscoverEntry>.translatedById(): Map<String, Int> =
    filter { it.translatedChapters > 0 }.associate { it.novel.id to it.translatedChapters }

fun List<DiscoverEntry>.overnightIds(): Set<String> =
    filter { it.overnightEnabled }.mapTo(HashSet()) { it.novel.id }

fun List<DiscoverEntry>.categoriesById(): Map<String, String> =
    filter { it.category.isNotBlank() }.associate { it.novel.id to it.category }

object DiscoverCategoryClassifier {
    const val ALL = "Tất cả"
    const val OVERNIGHT = "Đang dịch ngầm"
    const val AI_TRANSLATED = "Đã dịch AI"

    fun classify(novel: Novel): String {
        val blob = "${novel.title} ${novel.titleVi} ${novel.description}".lowercase()
        return when {
            listOf("học bá", "khoa học", "nghiên cứu", "viện sĩ", "giáo sư", "toán học", "vật lý", "công nghệ", "hắc khoa kỹ", "学霸", "科研", "院士", "教授", "科技").any { it in blob } -> "Khoa học & Học bá"
            listOf("đồng nhân", "chư thiên", "vạn giới", "hải tặc", "hokage", "nhẫn giả", "marvel", "mỹ mạn", "quần chat", "tổng mạn", "mô phỏng", "同人", "诸天", "万界", "海贼", "火影", "木叶", "漫威").any { it in blob } -> "Đồng nhân & Chư thiên"
            listOf("lịch sử", "đại đường", "đại minh", "đại tống", "tam quốc", "đại phụng", "hoàng đế", "vương triều", "quân sự", "历史", "大唐", "大明", "大宋", "三国", "皇帝").any { it in blob } -> "Lịch sử & Quân sự"
            listOf("trọng sinh", "trùng sinh", "đô thị", "thập niên", "197", "198", "199", "200", "làm giàu", "thương nghiệp", "tài phiệt", "giải trí", "sống lại", "重生", "都市", "年代", "发家", "首富").any { it in blob } -> "Đô thị & Trọng sinh"
            else -> "Tiên hiệp & Huyền huyễn"
        }
    }
}

/**
 * Kết quả một lần tải Khám phá. [personalized] = false nghĩa là danh sách là bảng chung (khách, tủ sách chưa đủ để
 * cá nhân hoá, máy chủ cũ...) dù đã xin "Dành cho bạn" — giao diện nên đặt tiêu đề cho đúng.
 */
data class DiscoverResult(val entries: List<DiscoverEntry>, val personalized: Boolean = false) {
    val novels: List<Novel> get() = entries.map { it.novel }
}

/** Chuyển phản hồi `/v1/novels/discover` thành [Novel] để mở trang chi tiết như kết quả tìm kiếm. */
object DiscoverMapper {
    /** Lý do dài bất thường bị cắt: nó nằm trong thẻ truyện và trong bản lưu bền. */
    const val MAX_REASON = 120

    /**
     * Cùng quy ước với kết quả tìm kiếm (NovelRepository.searchNovels): `id` lấy id chuẩn của máy chủ,
     * thiếu thì dùng địa chỉ nguồn; `url` là địa chỉ nguồn để trang chi tiết tự cào khi máy chủ chưa có.
     * Bỏ mục thiếu tên/định danh và mục trùng id (giữ thứ tự máy chủ đã xếp).
     */
    fun toNovels(response: DiscoverResponse): List<Novel> = toEntries(response).map { it.novel }

    /** Như [toNovels] (cùng luật bỏ mục / khử trùng) nhưng giữ lại lý do gợi ý; thiếu lý do thì là "". */
    fun toEntries(response: DiscoverResponse): List<DiscoverEntry> {
        val seen = HashSet<String>()
        val out = ArrayList<DiscoverEntry>(response.novels.size)
        for (item in response.novels) {
            val url = item.source_url.trim()
            val id = item.id.trim().ifBlank { url }
            val titleVi = item.title_vi.orEmpty().trim()
            val title = item.title.trim().ifBlank { titleVi }
            if (id.isBlank() || title.isBlank() || !seen.add(id)) continue
            val novel = Novel(
                id = id,
                title = title,
                titleVi = titleVi,
                author = item.author.orEmpty().trim(),
                url = url,
                coverUrl = item.cover_url.orEmpty().trim(),
                sourceId = item.source_id.trim(),
                totalChapters = item.total_chapters.coerceAtLeast(0),
                description = item.description.orEmpty().trim(),
                status = item.status.orEmpty().trim().ifBlank { "ongoing" }
            )
            val cat = item.category.orEmpty().trim().ifBlank { DiscoverCategoryClassifier.classify(novel) }
            val trCount = item.translated_chapters.coerceAtLeast(0)
            if (trCount > 0 || item.overnight_enabled) {
                ServerNovelMetaStore.record(id, trCount, item.overnight_enabled)
            }
            out += DiscoverEntry(
                novel = novel,
                reason = cleanReason(item.reason),
                translatedChapters = trCount,
                overnightEnabled = item.overnight_enabled,
                category = cat
            )
        }
        return out
    }

    fun toResult(response: DiscoverResponse): DiscoverResult = DiscoverResult(toEntries(response), response.personalized)

    private fun cleanReason(raw: String?): String = TextNormalize.collapse(raw.orEmpty()).take(MAX_REASON).trimEnd()
}

object DiscoverPolicy {
    /** Số truyện xin mỗi bảng xếp hạng (máy chủ hỗ trợ tới 500 để bao phủ trọn kho 1TB). */
    const val PAGE_SIZE = 500

    /** Số truyện lên băng chuyền đầu trang. */
    const val CAROUSEL_SIZE = 5

    /** Bản lưu còn "tươi" thì mở lại tab không cần gọi mạng. */
    const val FRESH_MS = 5 * 60 * 1000L

    fun isFresh(savedAt: Long, now: Long): Boolean = savedAt > 0L && now >= savedAt && now - savedAt < FRESH_MS
}
