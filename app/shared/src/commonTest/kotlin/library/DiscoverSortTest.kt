package com.truyencloud.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoverSortTest {

    @Test
    fun forYouComesFirstAndTheReleasedValuesAreUntouched() {
        assertEquals(
            listOf(DiscoverSort.FOR_YOU, DiscoverSort.RECENT, DiscoverSort.POPULAR),
            DiscoverSort.entries.toList(),
            "thứ tự khai báo = thứ tự chip"
        )
        assertEquals(listOf("for_you", "recent", "popular"), DiscoverSort.entries.map { it.apiValue })
        assertEquals(listOf("Dành cho bạn", "Mới cập nhật", "Đọc nhiều"), DiscoverSort.entries.map { it.label })
        // Bản 1.1.x đã phát hành gửi đúng hai giá trị này lên máy chủ
        assertEquals("recent", DiscoverSort.RECENT.apiValue)
        assertEquals("popular", DiscoverSort.POPULAR.apiValue)
    }

    @Test
    fun onlyForYouIsPersonal() {
        assertTrue(DiscoverSort.FOR_YOU.isPersonal)
        assertFalse(DiscoverSort.RECENT.isPersonal)
        assertFalse(DiscoverSort.POPULAR.isPersonal)
    }

    @Test
    fun loggedInReadersLandOnForYouAndGuestsOnRecent() {
        assertEquals(DiscoverSort.FOR_YOU, DiscoverSort.defaultFor(loggedIn = true))
        assertEquals(DiscoverSort.RECENT, DiscoverSort.defaultFor(loggedIn = false))
    }

    @Test
    fun guestsNeverGetTheForYouChip() {
        assertEquals(listOf(DiscoverSort.RECENT, DiscoverSort.POPULAR), DiscoverSort.available(loggedIn = false))
        assertEquals(
            listOf(DiscoverSort.FOR_YOU, DiscoverSort.RECENT, DiscoverSort.POPULAR),
            DiscoverSort.available(loggedIn = true)
        )
    }

    @Test
    fun theDefaultIsAlwaysOneOfTheAvailableChips() {
        for (loggedIn in listOf(true, false)) {
            assertTrue(DiscoverSort.defaultFor(loggedIn) in DiscoverSort.available(loggedIn))
        }
    }

    @Test
    fun coerceKeepsAValidSelectionAndFallsBackWhenTheLoginStateTookItAway() {
        assertEquals(DiscoverSort.POPULAR, DiscoverSort.coerce(DiscoverSort.POPULAR, loggedIn = true))
        assertEquals(DiscoverSort.POPULAR, DiscoverSort.coerce(DiscoverSort.POPULAR, loggedIn = false))
        assertEquals(DiscoverSort.FOR_YOU, DiscoverSort.coerce(DiscoverSort.FOR_YOU, loggedIn = true))
        // Đăng xuất khi đang ở "Dành cho bạn": chip biến mất nên phải về bảng mặc định của khách
        assertEquals(DiscoverSort.RECENT, DiscoverSort.coerce(DiscoverSort.FOR_YOU, loggedIn = false))
    }
}
