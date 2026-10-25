package com.truyencloud.shared.library

import com.truyencloud.shared.data.Novel

/** Các nhóm gợi ý tìm kiếm, theo thứ tự hiển thị; [heading] là tiêu đề nhóm. */
enum class SearchSuggestionKind(val heading: String) {
    /** Những gì chính tài khoản này vừa tìm. */
    RECENT("Tìm gần đây"),

    /** Tên truyện / tác giả trong tủ sách của tài khoản, đọc gần nhất trước. */
    LIBRARY("Từ tủ sách của bạn"),

    /** Truyện máy chủ gợi ý riêng cho tài khoản (bảng "Dành cho bạn") mà tài khoản chưa có trong tủ. */
    FOR_YOU("Gợi ý cho bạn"),

    /** Danh sách mặc định, chỉ để điền cho đủ khi chưa có nhiều thứ cá nhân ([SearchDefaults]). */
    DEFAULT("Gợi ý tìm kiếm nhanh")
}

/**
 * Một nhóm chip gợi ý. [hints]: chip → lý do máy chủ gợi ý (chỉ nhóm [SearchSuggestionKind.FOR_YOU], chỉ chip có lý do)
 * để giao diện hiện làm chú thích nếu muốn.
 */
data class SearchSuggestionSection(
    val kind: SearchSuggestionKind,
    val chips: List<String>,
    val hints: Map<String, String> = emptyMap()
) {
    val heading: String get() = kind.heading
}

/**
 * Dựng gợi ý tìm kiếm CÁ NHÂN cho màn hình tìm kiếm: mỗi tài khoản có tủ sách, lịch sử tìm và bảng "Dành cho bạn" riêng
 * nên danh sách khác nhau; danh sách cứng chung cho mọi người ([SearchDefaults]) chỉ còn là phần điền. Thuần, không
 * đụng mạng hay cài đặt — mọi đầu vào do người gọi truyền (đã thuộc về tài khoản hiện tại).
 *
 * Luật:
 *  - Nhóm theo thứ tự RECENT, LIBRARY, FOR_YOU, DEFAULT; nhóm rỗng bị bỏ.
 *  - Tối đa [MAX_CHIPS] chip cả thảy, không trùng nhau (không phân biệt hoa thường) giữa các nhóm: chip thuộc nhóm
 *    đứng trước. Ba nhóm cá nhân chia chỗ đều nhau (chia lần lượt từng chip), nhóm ít hơn phần chia thì nhường chỗ
 *    cho nhóm còn lại — người tìm nhiều không làm mất gợi ý từ tủ sách và ngược lại.
 *  - LIBRARY: truyện đọc gần nhất trước ([Novel.lastReadAt]); mỗi truyện cho tên tiếng Việt (nếu có, không thì tên gốc)
 *    rồi tên tác giả (bỏ tên nguồn / "Unknown" mà máy chủ điền khi không biết tác giả).
 *  - FOR_YOU: bỏ truyện đã có trong tủ (theo id / địa chỉ nguồn / tên đã hiện).
 *  - DEFAULT chỉ xuất hiện khi các nhóm cá nhân chưa đủ [MIN_PERSONAL_CHIPS] chip: khi đó điền cho tới [MAX_CHIPS]
 *    (tài khoản chưa có gì cá nhân thì toàn bộ danh sách mặc định).
 */
object SearchSuggestionBuilder {
    const val MAX_CHIPS = 12
    const val MIN_PERSONAL_CHIPS = 6

    /** Chip quá dài (tên bị lỗi cào) thì bỏ: bấm vào cũng không phải từ khoá người ta gõ. */
    const val MAX_CHIP_LENGTH = SearchHistoryStore.MAX_QUERY_LENGTH

    /**
     * Giá trị máy chủ điền vào `author` khi không biết tác giả: "Unknown", tên nguồn (69书吧, 晋江文学城, UU看书, 飘天文学),
     * "Khuyết danh", "Không rõ tác giả". Dạng đã hạ chữ thường.
     */
    private val PLACEHOLDER_AUTHORS = setOf(
        "unknown", "n/a", "-", "未知", "không rõ", "không rõ tác giả", "khuyết danh", "đang cập nhật",
        "69书吧", "晋江文学城", "uu看书", "飘天文学"
    )

    fun build(
        recent: List<String>,
        library: List<Novel>,
        forYou: List<DiscoverEntry>,
        defaults: List<String> = SearchDefaults.QUICK_SUGGESTIONS
    ): List<SearchSuggestionSection> {
        // Chip của nhóm đứng trước chặn chip trùng của nhóm đứng sau
        val claimed = HashSet<String>()
        val recentChips = ArrayList<String>()
        for (query in recent) if (!SearchHistoryStore.looksLikeUrl(query)) addChip(recentChips, claimed, query)
        val libraryChips = libraryChips(library, claimed)
        val forYouHints = LinkedHashMap<String, String>()
        val forYouChips = forYouChips(forYou, library, claimed, forYouHints)

        val shown = deal(listOf(recentChips, libraryChips, forYouChips), MAX_CHIPS)
        val personal = shown.sumOf { it.size }
        val defaultChips = if (personal < MIN_PERSONAL_CHIPS) {
            val taken = HashSet<String>()
            shown.forEach { list -> list.mapTo(taken) { TextNormalize.key(it) } }
            ArrayList<String>().also { out ->
                for (text in defaults) {
                    if (out.size >= MAX_CHIPS - personal) break
                    addChip(out, taken, text)
                }
            }
        } else {
            emptyList()
        }

        return buildList {
            add(SearchSuggestionSection(SearchSuggestionKind.RECENT, shown[0]))
            add(SearchSuggestionSection(SearchSuggestionKind.LIBRARY, shown[1]))
            add(SearchSuggestionSection(SearchSuggestionKind.FOR_YOU, shown[2], forYouHints.filterKeys { it in shown[2] }))
            add(SearchSuggestionSection(SearchSuggestionKind.DEFAULT, defaultChips))
        }.filter { it.chips.isNotEmpty() }
    }

    /** Tổng số chip của các nhóm. */
    fun chipCount(sections: List<SearchSuggestionSection>): Int = sections.sumOf { it.chips.size }

    private fun libraryChips(library: List<Novel>, claimed: MutableSet<String>): List<String> {
        val byRecency = compareByDescending<Novel> { it.lastReadAt }.thenByDescending { it.addedAt }.thenBy { it.id }
        val out = ArrayList<String>()
        for (novel in library.sortedWith(byRecency)) {
            addChip(out, claimed, novel.titleVi.ifBlank { novel.title })
            if (!isPlaceholderAuthor(novel.author)) addChip(out, claimed, novel.author)
        }
        return out
    }

    private fun forYouChips(
        entries: List<DiscoverEntry>,
        library: List<Novel>,
        claimed: MutableSet<String>,
        hints: MutableMap<String, String>
    ): List<String> {
        val libraryKeys = LibraryListLogic.libraryKeys(library)
        val out = ArrayList<String>()
        for (entry in entries) {
            if (LibraryListLogic.isInLibrary(entry.novel, libraryKeys)) continue
            val before = out.size
            addChip(out, claimed, entry.novel.titleVi.ifBlank { entry.novel.title })
            if (out.size > before && entry.reason.isNotBlank()) hints[out.last()] = entry.reason
        }
        return out
    }

    /** Thêm [text] vào [out] nếu hợp lệ và chưa ai giữ (khoá không phân biệt hoa thường). */
    private fun addChip(out: MutableList<String>, claimed: MutableSet<String>, text: String) {
        val chip = TextNormalize.collapse(text)
        if (chip.isEmpty() || chip.length > MAX_CHIP_LENGTH) return
        if (claimed.add(TextNormalize.key(chip))) out += chip
    }

    private fun isPlaceholderAuthor(author: String): Boolean {
        val key = TextNormalize.key(author)
        return key.isEmpty() || key in PLACEHOLDER_AUTHORS
    }

    /** Chia [budget] chỗ cho các danh sách, lần lượt từng chip cho mỗi danh sách còn hàng; mỗi danh sách giữ thứ tự riêng. */
    private fun deal(candidates: List<List<String>>, budget: Int): List<List<String>> {
        val taken = candidates.map { ArrayList<String>() }
        val next = IntArray(candidates.size)
        var left = budget
        var progressed = true
        while (left > 0 && progressed) {
            progressed = false
            for (i in candidates.indices) {
                if (left == 0) break
                if (next[i] < candidates[i].size) {
                    taken[i] += candidates[i][next[i]++]
                    left--
                    progressed = true
                }
            }
        }
        return taken
    }
}
