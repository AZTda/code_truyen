package com.truyencloud.shared.library

import com.truyencloud.shared.data.Novel

// Dữ liệu và hàm dựng dùng chung cho các bài kiểm thử gợi ý tìm kiếm.

internal fun suggestionBook(
    id: String,
    title: String = "Truyện $id",
    titleVi: String = "",
    author: String = "",
    lastReadAt: Long = 0L,
    addedAt: Long = 0L,
    url: String = ""
) = Novel(id = id, title = title, titleVi = titleVi, author = author, lastReadAt = lastReadAt, addedAt = addedAt, url = url)

internal fun suggestionPick(id: String, title: String = "Gợi ý $id", titleVi: String = "", reason: String = "", url: String = "") =
    DiscoverEntry(Novel(id = id, title = title, titleVi = titleVi, url = url), reason)

internal fun buildSuggestions(
    recent: List<String> = emptyList(),
    library: List<Novel> = emptyList(),
    forYou: List<DiscoverEntry> = emptyList(),
    defaults: List<String> = SearchDefaults.QUICK_SUGGESTIONS
) = SearchSuggestionBuilder.build(recent, library, forYou, defaults)

internal fun List<SearchSuggestionSection>.chipsOf(kind: SearchSuggestionKind) = firstOrNull { it.kind == kind }?.chips.orEmpty()
internal fun List<SearchSuggestionSection>.kinds() = map { it.kind }
internal fun List<SearchSuggestionSection>.allChips() = flatMap { it.chips }
internal fun lowerKeys(chips: List<String>) = chips.map { it.lowercase() }
