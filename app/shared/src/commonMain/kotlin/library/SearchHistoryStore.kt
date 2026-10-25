package com.truyencloud.shared.library

import com.truyencloud.shared.prefs.PrefKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Dạng lưu của lịch sử tìm kiếm: danh sách đi KÈM id chủ sở hữu để không bao giờ trao nhầm cho tài khoản khác. */
@Serializable
internal data class SearchHistoryDoc(val owner: String = "", val queries: List<String> = emptyList())

/**
 * Lịch sử tìm kiếm gần đây của MỘT tài khoản, lưu bền trong cài đặt ([PrefKeys.SEARCH_HISTORY]).
 *
 * HomeViewModel sống suốt phiên ứng dụng, qua cả đăng xuất / đăng nhập tài khoản khác, nên [ownerId] là hàm đọc id
 * hiện tại MỖI LẦN dùng (id hồ sơ; "guest" cho khách). Danh sách được ghi cùng id chủ: đọc bằng id khác thì trả rỗng
 * VÀ xoá luôn bản cũ, nên lịch sử của tài khoản này không bao giờ hiện cho tài khoản kia. Xoá dữ liệu trên máy
 * (đổi tài khoản / đăng xuất kèm xoá) cũng xoá nó — xem [AccountScopedPrefs].
 *
 * Không an toàn luồng: gọi từ luồng giao diện như các kho cài đặt khác.
 *
 * @param prefs null (nền tảng chưa khởi tạo kho cài đặt) thì chỉ nhớ trong RAM của phiên này.
 */
class SearchHistoryStore(
    private val prefs: HomePrefsStore?,
    private val ownerId: () -> String,
    maxEntries: Int = MAX_ENTRIES
) {
    private val capacity = maxEntries.coerceAtLeast(1)
    private var memory: SearchHistoryDoc? = null

    /**
     * Ghi nhớ một lượt tìm: bỏ khoảng trắng thừa, bỏ qua chuỗi rỗng / giống đường dẫn web / quá dài ([normalize]); trùng
     * (không phân biệt hoa thường) thì đưa lên đầu; giữ tối đa [MAX_ENTRIES] mục, mới nhất trước.
     * @return true nếu đã ghi nhớ.
     */
    fun add(query: String): Boolean {
        val q = normalize(query) ?: return false
        val owner = owner()
        val key = TextNormalize.key(q)
        val next = (listOf(q) + load(owner).filterNot { TextNormalize.key(it) == key }).take(capacity)
        store(SearchHistoryDoc(owner, next))
        return true
    }

    /** Tối đa [limit] lượt tìm gần nhất của tài khoản hiện tại, mới nhất trước; rỗng nếu bản lưu thuộc chủ khác. */
    fun recent(limit: Int = capacity): List<String> = load(owner()).take(limit.coerceAtLeast(0))

    /** Xoá toàn bộ lịch sử (người dùng chọn xoá). */
    fun clear() {
        memory = null
        wipe(prefs)
    }

    private fun owner() = HomePrefKeys.accountSlot(ownerId())

    private fun load(owner: String): List<String> {
        val doc = readDoc() ?: return emptyList()
        if (doc.owner != owner) {
            // Bản của người khác: bỏ hẳn, không chỉ ẩn đi
            clear()
            return emptyList()
        }
        // Bản lưu có thể hỏng / sửa tay: lọc lại bằng đúng luật khi ghi
        val seen = HashSet<String>()
        return doc.queries.mapNotNull { normalize(it) }.filter { seen.add(TextNormalize.key(it)) }.take(capacity)
    }

    private fun readDoc(): SearchHistoryDoc? {
        val p = prefs ?: return memory
        val raw = try { p.getString(PrefKeys.SEARCH_HISTORY) } catch (_: Exception) { null }
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(SearchHistoryDoc.serializer(), raw)
        } catch (_: Exception) {
            wipe(p)
            null
        }
    }

    private fun store(doc: SearchHistoryDoc) {
        val p = prefs
        if (p == null) {
            memory = doc
            return
        }
        try { p.putString(PrefKeys.SEARCH_HISTORY, json.encodeToString(SearchHistoryDoc.serializer(), doc)) } catch (_: Exception) { }
    }

    companion object {
        /** Số lượt tìm giữ lại. */
        const val MAX_ENTRIES = 12

        /** Từ khoá dài hơn thế không phải thứ người ta gõ lại (thường là đoạn văn dán vào) nên không được nhớ. */
        const val MAX_QUERY_LENGTH = 100

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        private val bareDomain = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}(:\\d+)?[/?#]")

        /** Từ khoá đã làm sạch (gộp khoảng trắng) nếu đáng nhớ; null nếu rỗng, giống đường dẫn web hoặc quá dài. */
        fun normalize(query: String): String? {
            val q = TextNormalize.collapse(query)
            if (q.isEmpty() || q.length > MAX_QUERY_LENGTH || looksLikeUrl(q)) return null
            return q
        }

        /** `http(s)://…`, `ftp://…`, `www.…` hoặc `miền.com/đường-dẫn…` — dán đường dẫn là để cào, không phải để gợi lại. */
        fun looksLikeUrl(text: String): Boolean {
            val t = text.trim().lowercase()
            return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www.") ||
                "://" in t || bareDomain.containsMatchIn(t)
        }

        /** Xoá bản lưu của MỌI chủ khỏi cài đặt (đường xoá dữ liệu trên máy dùng hàm này). */
        fun wipe(prefs: HomePrefsStore?) {
            try { prefs?.remove(PrefKeys.SEARCH_HISTORY) } catch (_: Exception) { }
        }
    }
}
