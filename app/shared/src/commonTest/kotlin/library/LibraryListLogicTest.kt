package com.truyencloud.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryListLogicTest {

    private val library = listOf(
        libraryNovel("r1", shelf = "reading", lastReadAt = 30, read = 3, total = 10, favorite = true),
        libraryNovel("r2", shelf = "reading", lastReadAt = 20, read = 0, total = 40),
        libraryNovel("c1", shelf = "completed", lastReadAt = 99, read = 12, total = 12, favorite = true),
        libraryNovel("p1", shelf = "planned", lastReadAt = 10, total = 8),
        libraryNovel("odd", shelf = "removed", lastReadAt = 5)
    )

    @Test
    fun unreadChaptersIsTotalMinusLastReadAndNeverNegative() {
        assertEquals(97, LibraryListLogic.unreadChapters(libraryNovel("a", total = 100, read = 3)))
        assertEquals(0, LibraryListLogic.unreadChapters(libraryNovel("b", total = 10, read = 10)))
        assertEquals(0, LibraryListLogic.unreadChapters(libraryNovel("c", total = 10, read = 25)))
        assertEquals(0, LibraryListLogic.unreadChapters(libraryNovel("d", total = 0, read = 4)))
    }

    @Test
    fun shelfCountsListEveryShelfAndFoldUnknownShelvesIntoReading() {
        val counts = LibraryListLogic.shelfCounts(library)
        assertEquals(3, counts["reading"], "r1, r2 và kệ lạ 'removed'")
        assertEquals(1, counts["completed"])
        assertEquals(1, counts["planned"])
        assertEquals(
            mapOf("reading" to 0, "completed" to 0, "planned" to 0),
            LibraryListLogic.shelfCounts(emptyList())
        )
    }

    @Test
    fun visibleShowsOnlyTheSelectedShelfSortedByTheChosenOrder() {
        val reading = LibraryListLogic.visible(library, "reading", favoritesOnly = false, sort = LibrarySort.RECENT)
        assertEquals(listOf("r1", "r2", "odd"), reading.ids())
        val completed = LibraryListLogic.visible(library, "completed", false, LibrarySort.RECENT)
        assertEquals(listOf("c1"), completed.ids())
        val planned = LibraryListLogic.visible(library, "planned", false, LibrarySort.UNREAD)
        assertEquals(listOf("p1"), planned.ids())
    }

    @Test
    fun favoritesFilterComposesWithTheShelf() {
        assertEquals(listOf("r1"), LibraryListLogic.visible(library, "reading", true, LibrarySort.RECENT).ids())
        assertEquals(listOf("c1"), LibraryListLogic.visible(library, "completed", true, LibrarySort.RECENT).ids())
        assertEquals(emptyList(), LibraryListLogic.visible(library, "planned", true, LibrarySort.RECENT).ids())
        assertEquals(1, LibraryListLogic.favoriteCount(library, "reading"))
        assertEquals(0, LibraryListLogic.favoriteCount(library, "planned"))
    }

    @Test
    fun unknownShelfArgumentIsTreatedAsReading() {
        assertEquals(
            LibraryListLogic.visible(library, "reading", false, LibrarySort.RECENT).ids(),
            LibraryListLogic.visible(library, "khong-ton-tai", false, LibrarySort.RECENT).ids()
        )
    }

    @Test
    fun movingToAShelfSkipsNovelsAlreadyThere() {
        val targets = listOf(library[0], library[2], library[4]) // r1 (reading), c1 (completed), odd (kệ lạ = reading)
        assertEquals(listOf("r1", "odd"), LibraryListLogic.needingShelfMove(targets, "completed").ids())
        assertEquals(listOf("c1"), LibraryListLogic.needingShelfMove(targets, "reading").ids())
        assertEquals(listOf("r1", "c1", "odd"), LibraryListLogic.needingShelfMove(targets, "planned").ids())
        // Kệ lạ được hiểu là "đang đọc": truyện nằm kệ lạ đã ở đúng chỗ
        assertEquals(listOf("c1"), LibraryListLogic.needingShelfMove(targets, "khong-ton-tai").ids())
        assertEquals(emptyList(), LibraryListLogic.needingShelfMove(emptyList(), "completed").ids())
    }

    @Test
    fun theFavoriteButtonRemovesTheHeartOnlyWhenEveryNovelHasIt() {
        val loved = listOf(library[0], library[2]) // r1, c1 đều yêu thích
        val unfav = LibraryListLogic.favoriteChange(loved)
        assertFalse(unfav.makeFavorite)
        assertEquals(listOf("r1", "c1"), unfav.toChange.ids())

        val mixed = listOf(library[0], library[1], library[3]) // r1 yêu thích, r2 và p1 chưa
        val fav = LibraryListLogic.favoriteChange(mixed)
        assertTrue(fav.makeFavorite, "lẫn lộn thì đặt yêu thích cho cả tập")
        assertEquals(listOf("r2", "p1"), fav.toChange.ids(), "chỉ đổi những truyện chưa yêu thích")

        val none = LibraryListLogic.favoriteChange(listOf(library[1], library[3]))
        assertTrue(none.makeFavorite)
        assertEquals(listOf("r2", "p1"), none.toChange.ids())

        val empty = LibraryListLogic.favoriteChange(emptyList())
        assertFalse(empty.makeFavorite)
        assertEquals(emptyList(), empty.toChange.ids())
    }

    @Test
    fun continueReadingSkipsUnreadAndCompletedNovelsNewestFirst() {
        assertEquals(listOf("r1"), LibraryListLogic.continueReading(library).ids())
        val more = library + libraryNovel("r3", shelf = "planned", lastReadAt = 50, read = 2, total = 9)
        assertEquals(listOf("r3", "r1"), LibraryListLogic.continueReading(more).ids())
    }

    @Test
    fun inLibraryMatchesByIdAndBySourceUrlIgnoringSchemeWwwAndTrailingSlash() {
        val keys = LibraryListLogic.libraryKeys(
            listOf(
                libraryNovel("jjwxc_2157106", url = "https://www.jjwxc.net/onebook.php?novelid=2157106"),
                libraryNovel("10_10527", url = "")
            )
        )
        assertTrue(LibraryListLogic.isInLibrary(libraryNovel("10_10527"), keys))
        assertTrue(LibraryListLogic.isInLibrary(libraryNovel("2157106", url = "http://jjwxc.net/onebook.php?novelid=2157106/"), keys))
        assertFalse(LibraryListLogic.isInLibrary(libraryNovel("other", url = "https://jjwxc.net/onebook.php?novelid=1"), keys))
        assertFalse(LibraryListLogic.isInLibrary(libraryNovel("other"), emptySet()))
        // id trống + url trống không được khớp nhầm với bất kỳ thứ gì
        assertFalse(LibraryListLogic.isInLibrary(libraryNovel(""), keys))
    }
}
