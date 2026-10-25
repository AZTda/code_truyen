package com.truyencloud.shared.reader

import com.truyencloud.shared.api.GlossaryServerTerm
import com.truyencloud.shared.data.TermMemory
import com.truyencloud.shared.text.VietnameseText

/** Nguồn gốc một dòng trong bảng thuật ngữ hiển thị. */
enum class GlossaryOrigin {
    /** Của người đọc, lưu trên máy: sửa / ghim / xoá được. */
    LOCAL,

    /** Máy chủ đang áp cho cả truyện do quản trị viên đặt (chỉ đọc). */
    SERVER_SHARED,

    /** Tên riêng AI tự trích sau khi dịch chương (chỉ đọc; muốn đổi thì ghi đè bằng mục của mình). */
    SERVER_AUTO
}

data class GlossaryEntry(
    val source: String,
    val target: String,
    val origin: GlossaryOrigin,
    /** Id trong Room khi [origin] là LOCAL. */
    val localId: String? = null,
    val pinned: Boolean = false,
    val hitCount: Int = 0,
    val updatedEpoch: Long = 0,
    /** Thuật ngữ dùng chung cho MỌI truyện (novelId rỗng). */
    val global: Boolean = false,
    /** Mục của người đọc đang ghi đè một mục cùng nguồn của máy chủ. */
    val overridesServer: Boolean = false,
    /** Cách dịch máy chủ đang dùng cho nguồn này (hiện kèm khi bị ghi đè). */
    val serverTarget: String? = null
) {
    val editable: Boolean get() = origin == GlossaryOrigin.LOCAL
    val isAuto: Boolean get() = origin == GlossaryOrigin.SERVER_AUTO

    /**
     * Khoá duy nhất của dòng trong danh sách: id Room cho mục trên máy (một nguyên văn có thể có cả mục riêng của truyện lẫn
     * mục dùng chung mọi truyện), còn mục máy chủ không bao giờ trùng nguyên văn với nhau.
     */
    val key: String get() = localId ?: "server|$source"
}

/**
 * Gộp bảng thuật ngữ trên máy với bảng máy chủ đang áp cho truyện (kể cả tên riêng AI tự trích).
 *
 * Trùng nguồn thì mục của người đọc thắng (máy chủ chỉ thay thế chuỗi theo bảng của nó, còn bảng gửi lên từ máy này
 * luôn được áp cho bản dịch của chính người đọc).
 */
object GlossaryMerge {

    /**
     * Thứ tự hiển thị: mục của mình (ghim trước, rồi mới sửa gần nhất), tiếp đến mục chung của máy chủ, cuối cùng là mục
     * tự động; trong nhóm máy chủ xếp nguồn DÀI trước (đúng thứ tự máy chủ áp dụng) rồi theo chữ cái.
     */
    fun merge(local: List<TermMemory>, server: List<GlossaryServerTerm>): List<GlossaryEntry> {
        val serverBySource = server.filter { it.source.isNotBlank() && it.target.isNotBlank() }.associateBy { it.source }
        val mine = local
            .filter { it.source.isNotBlank() && it.target.isNotBlank() }
            .sortedWith(
                compareByDescending<TermMemory> { it.pinned }
                    .thenByDescending { it.updatedEpoch }
                    .thenBy { it.source }
            )
            .map { t ->
                val srv = serverBySource[t.source]
                GlossaryEntry(
                    source = t.source,
                    target = t.target,
                    origin = GlossaryOrigin.LOCAL,
                    localId = t.id,
                    pinned = t.pinned,
                    hitCount = t.hitCount,
                    updatedEpoch = t.updatedEpoch,
                    global = t.novelId.isEmpty(),
                    overridesServer = srv != null && srv.target != t.target,
                    serverTarget = srv?.target
                )
            }
        val taken = mine.mapTo(HashSet()) { it.source }
        val remote = serverBySource.values
            .filter { it.source !in taken }
            .sortedWith(
                compareBy<GlossaryServerTerm> { it.auto }
                    .thenByDescending { it.source.length }
                    .thenBy { it.source }
            )
            .map { s ->
                GlossaryEntry(
                    source = s.source,
                    target = s.target,
                    origin = if (s.auto) GlossaryOrigin.SERVER_AUTO else GlossaryOrigin.SERVER_SHARED
                )
            }
        return mine + remote
    }

    /** Lọc theo nguồn hoặc cách dịch, không phân biệt hoa thường / dấu. */
    fun filter(entries: List<GlossaryEntry>, query: String): List<GlossaryEntry> {
        val needle = VietnameseText.fold(query.trim())
        if (needle.isEmpty()) return entries
        return entries.filter { needle in VietnameseText.fold(it.source) || needle in VietnameseText.fold(it.target) }
    }
}

/** Kiểm tra / xử lý chữ cho bảng thuật ngữ và tra cứu điển tích. */
object GlossaryTerms {
    // Máy chủ bỏ im lặng mục dài hơn các mức này (services/glossary_store.py), nên chặn ngay từ ứng dụng để báo rõ
    const val MAX_SOURCE_CHARS = 32
    const val MAX_TARGET_CHARS = 80
    const val MAX_CONTEXT_CHARS = 1200

    const val MSG_NEED_BOTH = "Cần nhập cả nguyên văn và cách dịch."
    const val MSG_SAME = "Cách dịch phải khác nguyên văn thì mới có tác dụng."
    const val MSG_SOURCE_TOO_LONG = "Nguyên văn dài quá $MAX_SOURCE_CHARS ký tự — hãy chọn riêng tên cần ghim."
    const val MSG_TARGET_TOO_LONG = "Cách dịch dài quá $MAX_TARGET_CHARS ký tự."
    const val MSG_MULTILINE = "Nguyên văn và cách dịch phải nằm trên một dòng."

    /** Lý do mục này không dùng được, hoặc null nếu hợp lệ. */
    fun validate(source: String, target: String): String? {
        val s = source.trim()
        val t = target.trim()
        return when {
            s.isEmpty() || t.isEmpty() -> MSG_NEED_BOTH
            '\n' in s || '\n' in t -> MSG_MULTILINE
            s == t -> MSG_SAME
            s.length > MAX_SOURCE_CHARS -> MSG_SOURCE_TOO_LONG
            t.length > MAX_TARGET_CHARS -> MSG_TARGET_TOO_LONG
            else -> null
        }
    }

    private fun isCjk(c: Char): Boolean =
        c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿'

    /** Chuỗi có chữ Hán: chọn từ bản GỐC thì nguyên văn khớp chính xác với văn bản gửi đi dịch. */
    fun containsCjk(text: String): Boolean = text.any(::isCjk)

    /**
     * Nguyên văn CHÍNH XÁC dùng làm khoá khi lưu: từ người đọc đã chọn trong bản gốc (có chữ Hán) thì khớp chắc chắn với chương;
     * từ chọn trong bản dịch tiếng Việt thì null — khi đó khoá là chữ Hán do AI đoán (có thể không khớp bản gốc).
     */
    fun exactSource(selectedTerm: String): String? = selectedTerm.trim().takeIf { it.isNotEmpty() && containsCjk(it) }

    /** Đoạn văn chủ yếu là chữ Hán (đang xem bản gốc) hay đã là bản dịch tiếng Việt. */
    fun looksChinese(text: String): Boolean {
        var cjk = 0
        var letters = 0
        for (c in text) {
            if (isCjk(c)) {
                cjk++
                letters++
            } else if (c.isLetter()) {
                letters++
            }
        }
        return cjk >= 2 && cjk * 2 >= letters
    }

    /** Ngữ cảnh gửi kèm tra cứu: đoạn quá dài thì cắt quanh lần xuất hiện đầu tiên của [term]. */
    fun clipContext(paragraph: String, term: String, max: Int = MAX_CONTEXT_CHARS): String {
        val text = paragraph.trim()
        if (text.length <= max) return text
        val at = text.indexOf(term.trim()).takeIf { it >= 0 } ?: 0
        val start = (at - max / 2).coerceIn(0, text.length - max)
        return text.substring(start, start + max)
    }
}
