package com.truyencloud.shared.library

/**
 * Tập truyện đang được chọn ở chế độ chọn nhiều. Bất biến: mỗi thao tác trả về bản mới.
 * Chế độ chọn BẬT khi còn ít nhất một truyện được chọn và tự TẮT khi bỏ chọn hết.
 */
data class LibrarySelection(val ids: Set<String> = emptySet()) {
    val isActive: Boolean get() = ids.isNotEmpty()
    val count: Int get() = ids.size

    operator fun contains(id: String): Boolean = id in ids

    /** Bắt đầu chế độ chọn với đúng một truyện (nhấn giữ). */
    fun start(id: String): LibrarySelection = LibrarySelection(setOf(id))

    fun add(id: String): LibrarySelection = if (id in ids) this else LibrarySelection(ids + id)

    fun toggle(id: String): LibrarySelection =
        if (id in ids) LibrarySelection(ids - id) else LibrarySelection(ids + id)

    fun selectAll(visibleIds: Collection<String>): LibrarySelection = LibrarySelection(visibleIds.toSet())

    fun isAllSelected(visibleIds: Collection<String>): Boolean =
        visibleIds.isNotEmpty() && ids.containsAll(visibleIds)

    /**
     * Bỏ những truyện không còn hiển thị (đổi kệ, đổi bộ lọc, bị xoá do đồng bộ) để thanh chọn
     * không đếm cả truyện người dùng không nhìn thấy.
     */
    fun retainOnly(visibleIds: Collection<String>): LibrarySelection {
        if (ids.isEmpty()) return this
        val keep = visibleIds.toSet()
        val next = ids.filterTo(LinkedHashSet()) { it in keep }
        return if (next.size == ids.size) this else LibrarySelection(next)
    }

    fun clear(): LibrarySelection = if (ids.isEmpty()) this else LibrarySelection()
}
