package com.truyencloud.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibrarySortTest {

    @Test
    fun recentOrdersByLastReadThenByAddedTime() {
        val novels = listOf(
            libraryNovel("old", lastReadAt = 100, addedAt = 1),
            libraryNovel("newest", lastReadAt = 900, addedAt = 2),
            libraryNovel("tieOlder", lastReadAt = 500, addedAt = 5),
            libraryNovel("tieNewer", lastReadAt = 500, addedAt = 50)
        )
        assertEquals(listOf("newest", "tieNewer", "tieOlder", "old"), LibrarySort.RECENT.sort(novels).ids())
    }

    @Test
    fun addedOrdersByAddedTimeNewestFirst() {
        val novels = listOf(
            libraryNovel("a", addedAt = 10, lastReadAt = 999),
            libraryNovel("b", addedAt = 30, lastReadAt = 1),
            libraryNovel("c", addedAt = 20, lastReadAt = 500)
        )
        assertEquals(listOf("b", "c", "a"), LibrarySort.ADDED.sort(novels).ids())
    }

    @Test
    fun titleSortIgnoresCaseAndVietnameseAccents() {
        val novels = listOf(
            libraryNovel("kiem", title = "剑来", titleVi = "Kiếm Lai"),
            libraryNovel("dai", title = "x", titleVi = "Đại Phụng Đả Canh Nhân"),
            libraryNovel("ai", title = "y", titleVi = "ái Tiềm Thủy"),
            libraryNovel("boston", title = "z", titleVi = "chuyện tình Boston")
        )
        // fold: "ai tiem thuy" < "chuyen tinh boston" < "dai phung ..." < "kiem lai"
        assertEquals(listOf("ai", "boston", "dai", "kiem"), LibrarySort.TITLE.sort(novels).ids())
    }

    @Test
    fun unreadSortPutsTheMostUnreadChaptersFirst() {
        val novels = listOf(
            libraryNovel("ninety", total = 100, read = 10),
            libraryNovel("fifty", total = 50, read = 0),
            libraryNovel("finished", total = 20, read = 20, lastReadAt = 7),
            libraryNovel("unknownTotal", total = 0, read = 5, lastReadAt = 9)
        )
        val sorted = LibrarySort.UNREAD.sort(novels).ids()
        assertEquals(listOf("ninety", "fifty"), sorted.take(2))
        // Hai truyện không còn chương chưa đọc: truyện đọc gần đây hơn đứng trước
        assertEquals(listOf("unknownTotal", "finished"), sorted.drop(2))
    }

    @Test
    fun equalKeysFallBackToTitleThenIdSoOrderIsDeterministic() {
        val novels = listOf(
            libraryNovel("z2", title = "Bắc", lastReadAt = 5),
            libraryNovel("z1", title = "Bắc", lastReadAt = 5),
            libraryNovel("a1", title = "Ân", lastReadAt = 5)
        )
        assertEquals(listOf("a1", "z1", "z2"), LibrarySort.RECENT.sort(novels).ids())
        assertEquals(listOf("a1", "z1", "z2"), LibrarySort.RECENT.sort(novels.reversed()).ids())
    }

    @Test
    fun sortingDoesNotMutateTheInputAndHandlesTinyLists() {
        val input = listOf(libraryNovel("b", lastReadAt = 1), libraryNovel("a", lastReadAt = 2))
        LibrarySort.RECENT.sort(input)
        assertEquals(listOf("b", "a"), input.ids())
        assertEquals(emptyList(), LibrarySort.TITLE.sort(emptyList()).ids())
        assertEquals(listOf("only"), LibrarySort.UNREAD.sort(listOf(libraryNovel("only"))).ids())
    }

    @Test
    fun fromKeyRoundTripsAndFallsBackToRecent() {
        LibrarySort.entries.forEach { assertEquals(it, LibrarySort.fromKey(it.key)) }
        assertEquals(LibrarySort.RECENT, LibrarySort.fromKey(null))
        assertEquals(LibrarySort.RECENT, LibrarySort.fromKey(""))
        assertEquals(LibrarySort.RECENT, LibrarySort.fromKey("khong-ton-tai"))
    }

    @Test
    fun sortLabelsAreTheVietnameseNamesShownOnTheChips() {
        assertEquals(
            listOf("Đọc gần đây", "Mới thêm", "Tên A-Z", "Chưa đọc nhiều nhất"),
            LibrarySort.entries.map { it.label }
        )
    }
}
