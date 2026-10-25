package com.truyencloud.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryEmptyCopyTest {

    @Test
    fun everyShelfAndFilterCombinationHasItsOwnHelpfulMessage() {
        val seen = HashSet<String>()
        for (shelf in LibraryShelves.all.map { it.id } + "khong-ton-tai") {
            for (libraryIsEmpty in listOf(true, false)) {
                for (favoritesOnly in listOf(true, false)) {
                    val copy = LibraryEmptyCopy.of(shelf, libraryIsEmpty, favoritesOnly)
                    assertTrue(copy.title.isNotBlank() && copy.hint.isNotBlank(), "$shelf/$libraryIsEmpty/$favoritesOnly")
                    seen += copy.title
                }
            }
        }
        // Tủ trống, bộ lọc yêu thích và ba kệ: năm thông điệp khác nhau
        assertEquals(5, seen.size, seen.toString())
    }

    @Test
    fun anEmptyLibraryPointsToDiscoverSearchAndImport() {
        val copy = LibraryEmptyCopy.of(LibraryShelves.READING, libraryIsEmpty = true, favoritesOnly = false)
        assertEquals("Tủ sách còn trống", copy.title)
        assertTrue("Khám phá" in copy.hint && "nạp tệp" in copy.hint)
    }

    @Test
    fun theFavoritesFilterMessageWinsOverTheShelfMessageAndExplainsHowToClearIt() {
        val copy = LibraryEmptyCopy.of(LibraryShelves.COMPLETED, libraryIsEmpty = false, favoritesOnly = true)
        assertTrue("yêu thích" in copy.title)
        assertTrue("bỏ lọc" in copy.hint)
    }

    @Test
    fun theCompletedAndCollectionShelvesExplainHowToMoveABookThere() {
        val completed = LibraryEmptyCopy.of(LibraryShelves.COMPLETED, false, false)
        assertEquals("Chưa có truyện hoàn thành", completed.title)
        assertTrue("Hoàn thành" in completed.hint)

        val planned = LibraryEmptyCopy.of(LibraryShelves.PLANNED, false, false)
        assertEquals("Bộ sưu tập còn trống", planned.title)
        assertTrue("Bộ sưu tập" in planned.hint)
    }

    @Test
    fun anUnknownShelfIsTreatedLikeTheReadingShelf() {
        assertEquals(
            LibraryEmptyCopy.of(LibraryShelves.READING, false, false),
            LibraryEmptyCopy.of("removed", false, false)
        )
    }
}
