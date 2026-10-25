package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.reader.AiErrors
import com.truyencloud.shared.reader.GlossaryTerms
import com.truyencloud.shared.reader.LoreOutcome
import com.truyencloud.shared.reader.LorePolicy
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.TermMemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Tra cứu Hán Việt / điển tích và ghi nhớ cách dịch vào bảng thuật ngữ của truyện.
 *
 * Bản cũ khi tra lỗi tự bịa một định nghĩa ("Thuật ngữ hoặc danh xưng đặc thù...") và vẫn cho lưu nó vào bảng thuật ngữ — thứ
 * sẽ đi vào prompt của mọi lần dịch sau. Giờ kết quả dự phòng của máy chủ chỉ hiện thành lỗi kèm nút thử lại và không bao
 * giờ được lưu. Tra cứu nay cũng gửi kèm ĐOẠN VĂN chứa từ (ngữ cảnh) để AI hiểu đúng nghĩa trong truyện.
 */
internal class ReaderLoreCoordinator(
    private val chapterRepository: ChapterRepository,
    private val termMemoryRepository: TermMemoryRepository?,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope
) {
    private var lookupJob: Job? = null

    /** Chương mà [ReaderUiState.loreTerm] / loreContext / loreError đang giữ thuộc về (đổi chương thì các giá trị đó đã cũ). */
    private var stateChapterKey: String? = null

    /**
     * Mở bảng tra cứu. Có [initialTerm] / [context] (chọn từ một đoạn văn) thì bắt đầu một lượt mới và tra ngay nếu đã có từ;
     * mở trơn từ menu thì giữ nguyên lượt tra gần nhất.
     */
    fun open(initialTerm: String = "", context: String = "") {
        val term = initialTerm.trim()
        val fresh = term.isNotEmpty() || context.isNotBlank()
        val s = uiStateFlow.value
        if (fresh) {
            lookupJob?.cancel()
            stateChapterKey = s.chapterKey
            uiStateFlow.value = s.copy(
                showLoreLookup = true,
                loreTerm = term,
                loreContext = context.trim(),
                loreLookupData = null,
                loreError = null,
                isLoadingLore = false,
                isLoreTermSaved = false,
                loreTargetInput = "",
                loreSaveMessage = null,
                isSavingLoreTerm = false,
                // Mở từ bảng thuật ngữ thì bảng đó nhường chỗ (mỗi lúc chỉ một bảng)
                glossary = s.glossary.copy(visible = false)
            )
        } else {
            // Mở trơn từ menu: giữ lượt tra gần nhất của CHƯƠNG NÀY; sang chương khác thì từ / đoạn văn / lỗi cũ không còn liên quan
            val sameChapter = stateChapterKey == s.chapterKey
            stateChapterKey = s.chapterKey
            uiStateFlow.value = s.copy(
                showLoreLookup = true,
                loreSaveMessage = null,
                loreTerm = if (sameChapter) s.loreTerm else "",
                loreContext = if (sameChapter) s.loreContext else "",
                loreError = if (sameChapter) s.loreError else null,
                glossary = s.glossary.copy(visible = false)
            )
        }
        refreshGlossarySize()
        if (term.isNotEmpty()) lookup(term)
    }

    fun close() {
        uiStateFlow.value = uiStateFlow.value.copy(showLoreLookup = false, loreSaveMessage = null)
    }

    fun onTargetChange(value: String) {
        uiStateFlow.value = uiStateFlow.value.copy(loreTargetInput = value, loreSaveMessage = null)
    }

    fun refreshGlossarySize() {
        val repo = termMemoryRepository ?: return
        val novelId = uiStateFlow.value.novel?.id ?: return
        scope.launch {
            try {
                uiStateFlow.value = uiStateFlow.value.copy(glossarySize = repo.count(novelId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Chỉ là con số hiển thị; thiếu thì giữ giá trị cũ
            }
        }
    }

    /** Tra lại đúng từ vừa tra (nút "Thử lại"). */
    fun retry() {
        val term = uiStateFlow.value.loreTerm
        if (term.isNotBlank()) lookup(term)
    }

    fun lookup(term: String) {
        val clean = term.trim()
        if (clean.isEmpty()) return
        lookupJob?.cancel()
        val s = uiStateFlow.value
        stateChapterKey = s.chapterKey
        val cleared = s.copy(
            loreTerm = clean,
            loreLookupData = null,
            loreError = null,
            isLoreTermSaved = false,
            loreTargetInput = "",
            loreSaveMessage = null,
            isSavingLoreTerm = false
        )
        // Máy chủ chỉ tra cứu cho tài khoản đã đăng nhập: khỏi gọi mạng chỉ để nhận 401
        if (TruyenCloudApi.authToken.isBlank()) {
            uiStateFlow.value = cleared.copy(isLoadingLore = false, loreError = AiErrors.MSG_LOGIN_REQUIRED)
            return
        }
        uiStateFlow.value = cleared.copy(isLoadingLore = true)
        val key = s.chapterKey
        val novelId = s.novel?.id.orEmpty()
        val context = GlossaryTerms.clipContext(s.loreContext, clean).ifBlank { null }
        lookupJob = scope.launch {
            try {
                val res = chapterRepository.explainLore(clean, context)
                if (stale(key, clean)) return@launch
                when (val outcome = LorePolicy.outcome(res)) {
                    is LoreOutcome.Failed ->
                        uiStateFlow.value = uiStateFlow.value.copy(isLoadingLore = false, loreError = outcome.message)
                    is LoreOutcome.Ready -> {
                        val lore = outcome.lore
                        val sourceKey = GlossaryTerms.exactSource(clean) ?: lore.hanzi.ifBlank { lore.term }
                        val known = if (novelId.isNotBlank()) termMemoryRepository?.find(novelId, sourceKey) else null
                        if (stale(key, clean)) return@launch
                        uiStateFlow.value = uiStateFlow.value.copy(
                            loreLookupData = lore,
                            isLoadingLore = false,
                            isLoreTermSaved = known != null,
                            loreTargetInput = known?.target ?: lore.sino_vietnamese
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stale(key, clean)) return@launch
                uiStateFlow.value = uiStateFlow.value.copy(isLoadingLore = false, loreError = AiErrors.message(e))
            }
        }
    }

    /** Chương đã đổi hoặc người đọc đã tra một từ khác trong lúc chờ: kết quả này không còn thuộc về màn hình. */
    private fun stale(key: String?, term: String): Boolean {
        val s = uiStateFlow.value
        return s.chapterKey != key || s.loreTerm != term
    }

    /**
     * Ghi nhớ cách dịch. Từ người đọc chọn trong BẢN GỐC (có chữ Hán) được lưu đúng nguyên văn đã chọn — chắc chắn có trong
     * chương; từ chọn trong bản dịch thì khoá là chữ Hán AI đoán (có thể không khớp bản gốc).
     */
    fun save(pinned: Boolean = false) {
        val repo = termMemoryRepository
        val s = uiStateFlow.value
        val lore = s.loreLookupData
        val novelId = s.novel?.id
        if (repo == null || lore == null || novelId.isNullOrBlank()) {
            uiStateFlow.value = s.copy(loreSaveMessage = "Chưa mở truyện nên chưa lưu được thuật ngữ.")
            return
        }
        val exact = GlossaryTerms.exactSource(s.loreTerm)
        val source = exact ?: lore.hanzi.ifBlank { lore.term }.trim()
        val target = s.loreTargetInput.trim().ifBlank { lore.sino_vietnamese.trim() }
        GlossaryTerms.validate(source, target)?.let { reason ->
            uiStateFlow.value = s.copy(loreSaveMessage = reason)
            return
        }
        uiStateFlow.value = s.copy(isSavingLoreTerm = true, loreSaveMessage = null)
        scope.launch {
            try {
                val saved = repo.saveFromLore(
                    novelId = novelId,
                    lore = lore,
                    preferredTarget = s.loreTargetInput,
                    pinned = pinned,
                    sourceOverride = exact
                )
                uiStateFlow.value = if (saved == null) {
                    uiStateFlow.value.copy(isSavingLoreTerm = false, loreSaveMessage = "Chưa lưu được thuật ngữ này.")
                } else {
                    uiStateFlow.value.copy(
                        isSavingLoreTerm = false,
                        isLoreTermSaved = true,
                        loreTargetInput = saved.target,
                        glossarySize = repo.count(novelId),
                        loreSaveMessage = if (saved.pinned) {
                            "Đã ghim — luôn được gửi kèm khi dịch \"${saved.target}\"."
                        } else {
                            "Đã ghi nhớ — các chương dịch sau sẽ dùng \"${saved.target}\"."
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiStateFlow.value = uiStateFlow.value.copy(
                    isSavingLoreTerm = false,
                    loreSaveMessage = "Không lưu được thuật ngữ: ${e.message ?: "lỗi không rõ"}"
                )
            }
        }
    }
}
