package com.truyencloud.shared.reader

import com.truyencloud.shared.data.Bookmark

/** Phạm vi danh sách dấu trang: chỉ chương đang đọc, hoặc cả truyện (nhóm theo chương). */
enum class BookmarkScope { CHAPTER, NOVEL }

data class BookmarkGroup(val chapterNumber: Int, val title: String, val bookmarks: List<Bookmark>) {
    /** "Chương 12 • Tên chương" (tên chương đã bắt đầu bằng "Chương 12" / "第12章" thì dùng nguyên). */
    val label: String
        get() {
            val t = title.trim()
            val numbered = t.startsWith("Chương $chapterNumber", ignoreCase = true) || t.startsWith("第${chapterNumber}章")
            return when {
                t.isEmpty() -> "Chương $chapterNumber"
                numbered -> t
                else -> "Chương $chapterNumber • $t"
            }
        }
}

object BookmarkGroups {

    /** Dấu trang hiển thị cho [scope]; bản đã xoá mềm không bao giờ hiện. */
    fun visible(all: List<Bookmark>, scope: BookmarkScope, currentChapter: Int): List<Bookmark> {
        val alive = all.filter { !it.isDeleted }
        return if (scope == BookmarkScope.CHAPTER) alive.filter { it.chapterNumber == currentChapter } else alive
    }

    /** Nhóm theo chương tăng dần; trong chương xếp theo đoạn rồi theo giờ tạo. */
    fun group(bookmarks: List<Bookmark>): List<BookmarkGroup> =
        bookmarks.groupBy { it.chapterNumber }
            .entries
            .sortedBy { it.key }
            .map { (chapter, items) ->
                BookmarkGroup(
                    chapterNumber = chapter,
                    title = items.firstNotNullOfOrNull { it.chapterTitle.trim().takeIf { t -> t.isNotEmpty() } }.orEmpty(),
                    bookmarks = items.sortedWith(compareBy<Bookmark> { it.paragraphIndex }.thenBy { it.createdAt })
                )
            }

    /** Dòng phụ dưới tiêu đề của bảng dấu trang. */
    fun summary(scope: BookmarkScope, groups: List<BookmarkGroup>, currentChapter: Int): String {
        val total = groups.sumOf { it.bookmarks.size }
        return when (scope) {
            BookmarkScope.CHAPTER -> "Chương $currentChapter • $total đoạn đánh dấu"
            BookmarkScope.NOVEL -> "$total dấu trang • ${groups.size} chương"
        }
    }

    /** Tệp Markdown cho danh sách đang hiển thị, nhóm theo chương (xuất cả truyện). */
    fun exportMarkdown(novelTitle: String, groups: List<BookmarkGroup>): Pair<String, String> {
        val safeTitle = novelTitle.trim().ifBlank { "truyencloud" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val content = buildString {
            appendLine("# ${novelTitle.ifBlank { "Truyện" }}")
            appendLine()
            groups.forEach { group ->
                appendLine("## ${group.label}")
                appendLine()
                group.bookmarks.forEachIndexed { index, bookmark ->
                    appendLine("### ${index + 1}. Đoạn ${bookmark.paragraphIndex + 1}")
                    appendLine()
                    appendLine("> ${bookmark.selectedText.replace("\n", " ")}")
                    if (bookmark.note.isNotBlank()) {
                        appendLine()
                        appendLine("**Ghi chú:** ${bookmark.note}")
                    }
                    appendLine()
                }
            }
            appendLine("---")
            appendLine("Xuất từ TruyenCloud")
        }
        return "${safeTitle}_bookmarks.md" to content
    }
}
