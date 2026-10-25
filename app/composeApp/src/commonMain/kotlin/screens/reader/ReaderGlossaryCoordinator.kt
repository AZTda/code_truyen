package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.api.GlossaryServerTerm
import com.truyencloud.shared.data.TermMemory
import com.truyencloud.shared.reader.AiErrors
import com.truyencloud.shared.reader.GlossaryEntry
import com.truyencloud.shared.reader.GlossaryMerge
import com.truyencloud.shared.reader.GlossaryTerms
import com.truyencloud.shared.repo.TermMemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Quản lý bảng thuật ngữ của truyện đang đọc. Trước đây `updateTarget / setPinned / delete / clearNovel / observe` của
 * [TermMemoryRepository] không có chỗ nào gọi từ giao diện: bảng chỉ lớn lên khi người đọc tra cứu, không xem hay sửa được.
 *
 * Danh sách = thuật ngữ trên máy (sửa / ghim / xoá được, tự cập nhật theo Room) gộp với bảng máy chủ đang áp cho truyện
 * (`GET /v1/novels/{id}/glossary`, gồm tên riêng AI tự trích, chỉ đọc — muốn đổi thì ghi đè bằng mục của mình).
 */
internal class ReaderGlossaryCoordinator(
    private val repo: TermMemoryRepository?,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    /** Dịch lại chương đang đọc (force) — bản dịch sẽ dùng bảng thuật ngữ hiện tại. */
    private val retranslateChapter: () -> Unit
) {
    private var observeJob: Job? = null
    private var serverJob: Job? = null
    private var messageJob: Job? = null
    private var local: List<TermMemory> = emptyList()
    private var server: List<GlossaryServerTerm> = emptyList()
    private var serverNotice: String? = null

    private val novelId: String? get() = uiStateFlow.value.novel?.id

    fun open() {
        val id = novelId ?: return
        val s = uiStateFlow.value
        local = emptyList()
        server = emptyList()
        serverNotice = null
        // Mỗi lúc chỉ một bảng: bảng tra cứu nhường chỗ
        uiStateFlow.value = s.copy(showLoreLookup = false, glossary = GlossaryUiState(visible = true, isLoading = repo != null))
        val repository = repo ?: return finishLoading()

        observeJob?.cancel()
        observeJob = scope.launch {
            try {
                repository.observe(id).collect { terms ->
                    local = terms
                    publish()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Không đọc được bảng trên máy: giữ danh sách rỗng, người đọc vẫn thêm được mục mới
            }
        }
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                server = repository.serverTerms(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                serverNotice = "Chưa lấy được thuật ngữ tự động từ máy chủ (${AiErrors.message(e)}). Đang hiện bảng trên máy."
            }
            publish(loading = false)
        }
    }

    fun close() {
        observeJob?.cancel()
        serverJob?.cancel()
        messageJob?.cancel()
        uiStateFlow.value = uiStateFlow.value.copy(glossary = uiStateFlow.value.glossary.copy(visible = false))
    }

    fun dismissMessage() = update { it.copy(message = null) }

    /** Sửa cách dịch. Mục của máy chủ (chỉ đọc) được ghi đè bằng một mục trên máy cùng nguyên văn. */
    fun saveTarget(entry: GlossaryEntry, newTarget: String) {
        val id = novelId ?: return
        val reason = GlossaryTerms.validate(entry.source, newTarget)
        if (reason != null) return say(reason)
        run("Không lưu được thuật ngữ") { repository ->
            val localId = entry.localId
            if (localId != null) repository.updateTarget(localId, newTarget) else repository.addManual(id, entry.source, newTarget)
            say("Đã lưu — chương dịch lại sẽ dùng \"${newTarget.trim()}\".")
        }
    }

    fun togglePinned(entry: GlossaryEntry) {
        val localId = entry.localId ?: return
        run("Không ghim được thuật ngữ") { it.setPinned(localId, !entry.pinned) }
    }

    fun delete(entry: GlossaryEntry) {
        val localId = entry.localId ?: return
        run("Không xoá được thuật ngữ") { repository ->
            repository.delete(localId)
            say("Đã xoá “${entry.source}”.")
        }
    }

    fun add(source: String, target: String) {
        val id = novelId ?: return
        val reason = GlossaryTerms.validate(source, target)
        if (reason != null) return say(reason)
        run("Không thêm được thuật ngữ") { repository ->
            repository.addManual(id, source, target)
            say("Đã thêm “${source.trim()}” → “${target.trim()}”.")
        }
    }

    /** Xoá mọi thuật ngữ riêng của truyện này (thuật ngữ dùng chung cho mọi truyện thì giữ nguyên). */
    fun clearAll() {
        val id = novelId ?: return
        run("Không xoá được bảng thuật ngữ") { repository ->
            repository.clearNovel(id)
            say("Đã xoá các thuật ngữ của truyện này.")
        }
    }

    fun retranslate() {
        close()
        retranslateChapter()
    }

    private fun run(failure: String, block: suspend (TermMemoryRepository) -> Unit) {
        val repository = repo ?: return say("Chưa mở được bảng thuật ngữ trên máy này.")
        scope.launch {
            try {
                block(repository)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("$failure: ${e.message ?: "lỗi không rõ"}")
            }
        }
    }

    private fun publish(loading: Boolean = uiStateFlow.value.glossary.isLoading) {
        update {
            it.copy(
                entries = GlossaryMerge.merge(local, server),
                isLoading = loading,
                serverNotice = serverNotice
            )
        }
        // Con số "N thuật ngữ đang được dùng" của bảng tra cứu theo kịp
        uiStateFlow.value = uiStateFlow.value.copy(glossarySize = local.size)
    }

    private fun finishLoading() = publish(loading = false)

    /** Thông báo ngắn dưới bảng; tự tắt sau vài giây (hoặc chạm vào để tắt ngay). */
    private fun say(text: String) {
        update { it.copy(message = text) }
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(MESSAGE_MS)
            update { it.copy(message = null) }
        }
    }

    private companion object {
        const val MESSAGE_MS = 5_000L
    }

    private fun update(transform: (GlossaryUiState) -> GlossaryUiState) {
        val s = uiStateFlow.value
        uiStateFlow.value = s.copy(glossary = transform(s.glossary))
    }
}
