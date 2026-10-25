package com.truyencloud.shared.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalDataMaintenanceTest {

    @Test
    fun everyUserTableIsWiped() {
        listOf(
            "novels", "chapters", "reading_sessions", "saved_words", "review_logs",
            "term_memories", "bookmarks", "sync_state"
        ).forEach { assertTrue(LocalDataMaintenance.isUserTable(it), it) }
    }

    @Test
    fun aTableAddedLaterIsWipedToo() {
        // Đây là lý do xoá theo sqlite_master thay vì một danh sách cố định
        assertTrue(LocalDataMaintenance.isUserTable("some_future_table"))
    }

    @Test
    fun roomAndSqliteInternalsAreNeverTouched() {
        // Xoá room_master_table sẽ làm Room báo sai danh tính cơ sở dữ liệu ở lần mở sau
        listOf("room_master_table", "room_table_modification_log", "sqlite_sequence", "sqlite_stat1", "android_metadata")
            .forEach { assertFalse(LocalDataMaintenance.isUserTable(it), it) }
    }
}
