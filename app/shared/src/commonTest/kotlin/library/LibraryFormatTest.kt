package com.truyencloud.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryFormatTest {

    @Test
    fun formatsByteSizesLikeTheStorageScreen() {
        assertEquals("0 KB", LibraryFormat.bytes(0))
        assertEquals("0 KB", LibraryFormat.bytes(-5))
        assertEquals("1 KB", LibraryFormat.bytes(300))
        assertEquals("512 KB", LibraryFormat.bytes(512L * 1024))
        assertEquals("1.0 MB", LibraryFormat.bytes(1024L * 1024))
        assertEquals("3.4 MB", LibraryFormat.bytes((3.4 * 1024 * 1024).toLong() + 1000))
        assertEquals("1.25 GB", LibraryFormat.bytes((1.25 * 1024 * 1024 * 1024).toLong() + 4096))
        assertEquals("2.05 GB", LibraryFormat.bytes((2.05 * 1024 * 1024 * 1024).toLong() + 4096))
    }

    @Test
    fun unreadLabelOnlyAppearsWhenThereIsSomethingLeftToRead() {
        assertEquals("Còn 12 chương", LibraryFormat.unreadLabel(12))
        assertNull(LibraryFormat.unreadLabel(0))
        assertNull(LibraryFormat.unreadLabel(-3))
    }
}
