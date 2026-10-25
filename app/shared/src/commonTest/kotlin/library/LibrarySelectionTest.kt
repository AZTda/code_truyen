package com.truyencloud.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LibrarySelectionTest {

    @Test
    fun startsInactiveAndLongPressStartsWithExactlyOneNovel() {
        val none = LibrarySelection()
        assertFalse(none.isActive)
        assertEquals(0, none.count)
        val one = none.start("a")
        assertTrue(one.isActive)
        assertEquals(setOf("a"), one.ids)
        assertTrue("a" in one)
    }

    @Test
    fun toggleAddsAndRemovesAndLeavesSelectionModeWhenTheLastOneIsDeselected() {
        var s = LibrarySelection().start("a").toggle("b")
        assertEquals(setOf("a", "b"), s.ids)
        s = s.toggle("a")
        assertEquals(setOf("b"), s.ids)
        s = s.toggle("b")
        assertFalse(s.isActive, "bỏ chọn truyện cuối cùng thì thoát chế độ chọn")
    }

    @Test
    fun addIsIdempotent() {
        val s = LibrarySelection().start("a")
        assertSame(s, s.add("a"))
        assertEquals(setOf("a", "b"), s.add("b").ids)
    }

    @Test
    fun selectAllAndIsAllSelectedFollowTheVisibleList() {
        val visible = listOf("a", "b", "c")
        val s = LibrarySelection().start("a")
        assertFalse(s.isAllSelected(visible))
        val all = s.selectAll(visible)
        assertEquals(3, all.count)
        assertTrue(all.isAllSelected(visible))
        assertFalse(all.isAllSelected(emptyList()), "danh sách rỗng không được coi là 'đã chọn tất cả'")
    }

    @Test
    fun retainOnlyDropsNovelsThatAreNoLongerVisible() {
        val s = LibrarySelection(setOf("a", "b", "c"))
        assertEquals(setOf("a", "c"), s.retainOnly(listOf("a", "c", "zzz")).ids)
        assertFalse(s.retainOnly(emptyList()).isActive, "đổi sang kệ không có truyện nào đang chọn thì thoát chế độ chọn")
        assertSame(s, s.retainOnly(listOf("a", "b", "c", "d")), "không đổi gì thì trả về chính nó để khỏi dựng lại giao diện")
    }

    @Test
    fun clearReturnsAnInactiveSelection() {
        val s = LibrarySelection(setOf("a", "b"))
        assertFalse(s.clear().isActive)
        val empty = LibrarySelection()
        assertSame(empty, empty.clear())
    }
}
