package com.truyencloud.shared.library

import com.truyencloud.shared.data.Novel

/** Dựng nhanh một truyện trong tủ cho các bài kiểm thử danh sách. */
internal fun libraryNovel(
    id: String,
    title: String = id,
    titleVi: String = "",
    total: Int = 0,
    read: Int = 0,
    lastReadAt: Long = 0L,
    addedAt: Long = 0L,
    shelf: String = LibraryShelves.READING,
    favorite: Boolean = false,
    url: String = ""
) = Novel(
    id = id,
    title = title,
    titleVi = titleVi,
    url = url,
    totalChapters = total,
    lastReadChapter = read,
    lastReadAt = lastReadAt,
    addedAt = addedAt,
    shelfName = shelf,
    isFavorite = favorite
)

internal fun List<Novel>.ids(): List<String> = map { it.id }
