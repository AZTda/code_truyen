package com.truyencloud.shared.export

import com.truyencloud.shared.data.Bookmark

object BookmarkExporter {
    fun exportMarkdown(
        bookmarks: List<Bookmark>,
        novelTitle: String,
        chapterTitle: String
    ): Pair<String, String> {
        val safeTitle = novelTitle.trim().ifBlank { "truyencloud" }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        val content = buildString {
            appendLine("# $novelTitle")
            appendLine()
            appendLine("## $chapterTitle")
            appendLine()
            bookmarks.forEachIndexed { index, bookmark ->
                appendLine("### ${index + 1}. Đoạn ${bookmark.paragraphIndex + 1}")
                appendLine()
                appendLine("> ${bookmark.selectedText.replace("\n", " ")}")
                if (bookmark.note.isNotBlank()) {
                    appendLine()
                    appendLine("**Ghi chú:** ${bookmark.note}")
                }
                appendLine()
            }
            appendLine("---")
            appendLine("Xuất từ TruyenCloud")
        }
        return "${safeTitle}_bookmarks.md" to content
    }
}
