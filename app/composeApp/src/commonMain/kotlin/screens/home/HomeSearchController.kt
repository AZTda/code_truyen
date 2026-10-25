package com.truyencloud.compose.screens.home

import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.repo.NovelRepository
import com.truyencloud.shared.text.VietnameseText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tìm kiếm: khớp tủ sách cục bộ tức thì theo từng phím gõ, kết quả web về sau thay thế; mất mạng thì
 * vẫn còn kết quả cục bộ. Dán URL thì cào để xem trước (không thêm vào tủ).
 */
internal class HomeSearchController(
    private val novelRepository: NovelRepository,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<HomeUiState>,
    private val novelApi: NovelApi? = null,
    /** Tủ sách hiện tại để khớp cục bộ. */
    private val library: () -> List<Novel>
) {
    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    fun open() {
        state.update { it.copy(isSearchActive = true) }
    }

    fun close() {
        searchJob?.cancel()
        suggestJob?.cancel()
        state.update {
            it.copy(
                isSearchActive = false,
                searchQuery = "",
                searchResults = emptyList(),
                liveSuggestions = emptyList(),
                searchError = null,
                isSearching = false
            )
        }
    }

    fun onQueryChange(query: String) {
        state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        suggestJob?.cancel()
        if (query.isBlank()) {
            // Xóa hết chữ (nút X của ô tìm kiếm hoặc xóa tay) phải đưa về gợi ý, kể cả khi lượt tìm trước đó báo lỗi
            state.update {
                it.copy(searchResults = emptyList(), liveSuggestions = emptyList(), searchError = null, isSearching = false)
            }
            return
        }
        val trimmed = query.trim()
        if (trimmed.length >= minQueryLength(trimmed) && !isUrl(trimmed)) {
            // Kết quả tủ sách local hiện NGAY (0ms), kết quả web về sau sẽ thay thế
            val instant = localMatches(trimmed)
            if (instant.isNotEmpty()) state.update { it.copy(searchResults = instant, searchError = null) }
            val api = novelApi
            if (api != null) {
                suggestJob = scope.launch {
                    delay(100)
                    try {
                        val resp = api.suggest(trimmed, limit = 6)
                        if (state.value.searchQuery.trim() == trimmed) {
                            state.update { it.copy(liveSuggestions = resp.suggestions) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
            searchJob = scope.launch {
                delay(400)
                perform()
            }
        } else {
            state.update { it.copy(liveSuggestions = emptyList()) }
        }
    }

    /** Chọn chip nguồn: lưu lại rồi tìm ngay để kết quả khớp với nguồn đang chọn. */
    fun onSelectSource(source: String) {
        state.update { it.copy(selectedSource = source) }
        if (state.value.searchQuery.isNotBlank()) perform(source)
    }

    fun perform(source: String? = null) {
        val q = state.value.searchQuery.trim()
        if (q.isBlank()) return

        val selected = source ?: state.value.selectedSource
        val filterSource = if (selected == "all" || selected.isBlank()) null else selected

        searchJob?.cancel()
        if (isUrl(q) && HomeMessages.isGuest()) {
            // Cào liên kết cần đăng nhập; tìm theo tên thì khách vẫn dùng được
            state.update {
                it.copy(isSearching = false, searchResults = emptyList(), searchError = HomeMessages.LINK_NEEDS_LOGIN)
            }
            return
        }
        searchJob = scope.launch {
            state.update { it.copy(isSearching = true, searchError = null) }
            try {
                val results = if (isUrl(q)) listOf(novelRepository.scrapeAndSave(q)) else novelRepository.searchNovels(q, filterSource)
                state.update { it.copy(isSearching = false, searchResults = results) }
            } catch (e: CancellationException) {
                // Gõ tiếp / đổi nguồn làm huỷ lượt cũ: không được ghi đè trạng thái của lượt mới
                throw e
            } catch (e: Exception) {
                // Mất mạng/lỗi máy chủ: vẫn hiện kết quả khớp từ tủ sách local nếu có
                val offline = if (isUrl(q)) emptyList() else localMatches(q)
                if (offline.isNotEmpty()) {
                    state.update { it.copy(isSearching = false, searchResults = offline, searchError = null) }
                } else {
                    state.update { it.copy(isSearching = false, searchError = FriendlyErrors.of(e)) }
                }
            }
        }
    }

    private fun isUrl(q: String) = q.startsWith("http://", ignoreCase = true) || q.startsWith("https://", ignoreCase = true)

    /** Một chữ Hán đã là từ khóa có nghĩa (剑, 仙); tiếng Việt cần >= 2 ký tự. */
    private fun minQueryLength(q: String): Int = if (VietnameseText.hasCjk(q)) 1 else 2

    private fun localMatches(query: String): List<Novel> {
        // So khớp không phân biệt dấu: gõ "kiem" vẫn thấy "Kiếm Lai"
        val q = VietnameseText.fold(query.trim())
        if (q.length < minQueryLength(q)) return emptyList()
        return library().filter {
            VietnameseText.fold(it.title).contains(q) ||
                VietnameseText.fold(it.titleVi).contains(q) ||
                VietnameseText.fold(it.author).contains(q)
        }
    }
}
