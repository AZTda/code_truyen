package com.truyencloud.shared.repo

import com.truyencloud.shared.api.GlossaryApi
import com.truyencloud.shared.api.GlossaryRemote
import com.truyencloud.shared.api.GlossaryServerTerm
import com.truyencloud.shared.api.LoreExplainResponse
import com.truyencloud.shared.api.TermRemote
import com.truyencloud.shared.data.TermMemory
import com.truyencloud.shared.data.TermMemoryDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.reader.GlossaryTerms
import kotlinx.coroutines.flow.Flow

/**
 * Trí nhớ thuật ngữ của từng bộ truyện.
 *
 * Đây là mảnh ghép còn thiếu của đường dẫn dịch: tham số `glossary` đã được nối sẵn
 * từ [com.truyencloud.shared.api.TranslateApi] xuống tới máy chủ nhưng chưa bao giờ
 * được điền, nên mỗi chương vẫn được dịch như thể chưa từng có chương nào trước đó.
 */
class TermMemoryRepository(
    private val dao: TermMemoryDao,
    /** Bảng thuật ngữ phía máy chủ (gồm tên riêng AI tự trích) để hiện cạnh bảng của người đọc. */
    private val remote: GlossaryRemote = GlossaryApi()
) {
    companion object {
        /**
         * Số thuật ngữ tối đa gửi kèm một lần dịch.
         *
         * Bảng thuật ngữ nằm trong prompt nên chiếm chỗ của chính nội dung chương.
         * 120 dòng (~3–4 nghìn ký tự) đủ cho dàn nhân vật của một bộ truyện dài
         * mà vẫn còn thừa ngữ cảnh cho chương.
         */
        const val MAX_GLOSSARY_TERMS = 120

        /** Thuật ngữ dùng chung cho mọi truyện. */
        const val GLOBAL_NOVEL_ID = ""

        fun idOf(novelId: String, source: String): String = "$novelId|$source"
    }

    private fun now(): Long = getCurrentEpochMillis()

    /**
     * Bảng thuật ngữ để gửi kèm lần dịch tới.
     *
     * Trả về theo thứ tự nguồn DÀI trước NGẮN: máy chủ thay thế chuỗi tuần tự nên nếu
     * "麻省" chạy trước "麻省理工" thì tên trường sẽ bị cắt đôi thành chuỗi vô nghĩa.
     */
    suspend fun glossaryFor(novelId: String): List<TermRemote> =
        dao.getGlossaryFor(novelId, MAX_GLOSSARY_TERMS)
            .filter { it.source.isNotBlank() && it.target.isNotBlank() }
            .sortedByDescending { it.source.length }
            .map { TermRemote(source = it.source, target = it.target) }

    fun observe(novelId: String): Flow<List<TermMemory>> = dao.observeForNovel(novelId)

    suspend fun count(novelId: String): Int = dao.countForNovel(novelId)

    suspend fun find(novelId: String, source: String): TermMemory? =
        dao.findIncludingGlobal(novelId, source)

    /** Bảng máy chủ đang áp cho truyện (`auto = true` là tên riêng AI tự trích). Ném lỗi khi không gọi được máy chủ. */
    suspend fun serverTerms(novelId: String): List<GlossaryServerTerm> = remote.fetchTerms(novelId)

    /**
     * Lưu kết quả tra cứu điển tích thành một mục trong bảng thuật ngữ.
     *
     * Trước đây màn tra cứu chỉ hiện rồi bỏ: người đọc tra 岑星, đọc xong, và lần dịch
     * kế tiếp vẫn không biết gì về cái tên đó.
     *
     * Kết quả dự phòng của máy chủ (`is_fallback`, không có nghĩa thật) KHÔNG BAO GIỜ được lưu: nó sẽ đi vào prompt của
     * mọi lần dịch sau.
     *
     * @param preferredTarget cách dịch người đọc tự gõ; để trống thì lấy Hán Việt do AI trả về.
     * @param sourceOverride nguyên văn CHÍNH XÁC người đọc đã chọn trong bản gốc. Nên truyền khi có: chữ Hán AI "đoán"
     *   ([LoreExplainResponse.hanzi]) có thể lệch văn bản thật (giản thể / phồn thể, thêm họ...) nên mục không bao giờ
     *   khớp; còn nguyên văn đã chọn thì chắc chắn có trong chương.
     */
    suspend fun saveFromLore(
        novelId: String,
        lore: LoreExplainResponse,
        preferredTarget: String = "",
        category: String = "other",
        pinned: Boolean = false,
        global: Boolean = false,
        sourceOverride: String? = null
    ): TermMemory? {
        if (lore.is_fallback) return null
        val source = sourceOverride?.trim()?.takeIf { it.isNotEmpty() } ?: lore.hanzi.ifBlank { lore.term }.trim()
        val target = preferredTarget.trim().ifBlank { lore.sino_vietnamese.trim() }
        // Rỗng, tự dịch thành chính nó (không có tác dụng mà vẫn tốn chỗ trong prompt) hoặc dài tới mức máy chủ bỏ im lặng
        if (GlossaryTerms.validate(source, target) != null) return null

        val scope = if (global) GLOBAL_NOVEL_ID else novelId
        val existing = dao.find(scope, source)
        val ts = now()
        val entry = TermMemory(
            id = idOf(scope, source),
            novelId = scope,
            source = source,
            target = target,
            hanzi = lore.hanzi,
            pinyin = lore.pinyin,
            sinoVietnamese = lore.sino_vietnamese,
            definition = lore.definition,
            loreContext = lore.lore_context,
            category = category,
            pinned = pinned || (existing?.pinned ?: false),
            hitCount = (existing?.hitCount ?: 0) + 1,
            createdEpoch = existing?.createdEpoch ?: ts,
            updatedEpoch = ts
        )
        dao.upsert(entry)
        return entry
    }

    /**
     * Thêm / ghi đè tay một thuật ngữ (không cần AI tra cứu). Trả null nếu mục không hợp lệ ([GlossaryTerms.validate]).
     * Mục cùng nguyên văn đã có thì giữ lại dữ liệu tra cứu và lượt tra, chỉ đổi cách dịch.
     */
    suspend fun addManual(
        novelId: String,
        source: String,
        target: String,
        pinned: Boolean = false,
        global: Boolean = false
    ): TermMemory? {
        val s = source.trim()
        val t = target.trim()
        if (GlossaryTerms.validate(s, t) != null) return null
        val scope = if (global) GLOBAL_NOVEL_ID else novelId
        val existing = dao.find(scope, s)
        val ts = now()
        val entry = TermMemory(
            id = idOf(scope, s),
            novelId = scope,
            source = s,
            target = t,
            hanzi = existing?.hanzi ?: if (GlossaryTerms.containsCjk(s)) s else "",
            pinyin = existing?.pinyin.orEmpty(),
            sinoVietnamese = existing?.sinoVietnamese.orEmpty(),
            definition = existing?.definition.orEmpty(),
            loreContext = existing?.loreContext.orEmpty(),
            category = existing?.category ?: "other",
            pinned = pinned || (existing?.pinned ?: false),
            hitCount = existing?.hitCount ?: 0,
            createdEpoch = existing?.createdEpoch ?: ts,
            updatedEpoch = ts
        )
        dao.upsert(entry)
        return entry
    }

    suspend fun updateTarget(id: String, target: String) {
        val t = target.trim()
        if (t.isBlank()) return
        dao.updateTarget(id, t, now())
    }

    suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned, now())

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun clearNovel(novelId: String) = dao.deleteAllForNovel(novelId)
}
