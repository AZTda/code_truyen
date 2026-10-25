package com.truyencloud.compose.screens.reader

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tìm trong chương. Tìm trên đúng văn bản đang hiển thị (đã nạp bằng getChapter) — KHÔNG đọc nội dung từ danh sách
 * chương (danh sách nhẹ không có nội dung).
 */
internal class ReaderFindCoordinator(
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val jumpTo: (paragraphIndex: Int) -> Unit
) {
    private var state: ReaderUiState
        get() = uiStateFlow.value
        set(value) { uiStateFlow.value = value }

    fun open() {
        state = state.copy(showFindSheet = true, findQuery = "", findMatches = emptyList(), findCursor = 0)
    }

    fun close() { state = state.copy(showFindSheet = false) }

    fun onQueryChange(query: String) {
        val matches = if (query.isBlank()) {
            emptyList()
        } else {
            val needle = query.trim().lowercase()
            state.displayParagraphs.mapIndexedNotNull { index, paragraph ->
                if (paragraph.lowercase().contains(needle)) index else null
            }
        }
        state = state.copy(findQuery = query, findMatches = matches, findCursor = 0)
        matches.firstOrNull()?.let(jumpTo)
    }

    fun previous() = move(-1)
    fun next() = move(1)

    private fun move(delta: Int) {
        val matches = state.findMatches
        if (matches.isEmpty()) return
        val next = (state.findCursor + delta + matches.size) % matches.size
        state = state.copy(findCursor = next)
        jumpTo(matches[next])
    }
}
