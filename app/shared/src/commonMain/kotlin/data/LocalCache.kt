package com.truyencloud.shared.data

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [
        Novel::class,
        Chapter::class,
        ReadingSession::class,
        SavedWord::class,
        ReviewLog::class,
        TermMemory::class,
        Bookmark::class,
        SyncState::class
    ],
    version = 11,
    // v7 -> v8: bo sung cac cot FSRS + ngu canh goc cho saved_words va them bang review_logs.
    // v8 -> v9: them bang term_memories (tri nho thuat ngu / ten rieng theo tung truyen).
    // v9 -> v10: them bang bookmarks (danh dau, highlight & ghi chu theo chuong / doan van).
    // v10 -> v11: saved_words them deletedAt (xoa mem / bia mo dong bo) + extraContextsJson;
    //             review_logs them isPractice, stateBefore, isSynced + index (wordId, reviewedAt).
    //             Chi them cot co gia tri mac dinh va index khong-unique, khong xoa gi.
    //             novels them syncUpdatedAt + syncDirty (hang doi dong bo tien do); bookmarks them
    //             updatedAt + deletedAt + syncDirty (xoa mem / hop nhat theo gio sua); bang moi
    //             sync_state (con tro dong bo + bia mo xoa khoi tu sach). Chi them, khong xoa gi.
    autoMigrations = [
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11)
    ],
    exportSchema = true
)
@ConstructedBy(LocalCacheConstructor::class)
abstract class LocalCache : RoomDatabase() {
    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun savedWordDao(): SavedWordDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun termMemoryDao(): TermMemoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun syncStateDao(): SyncStateDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object LocalCacheConstructor : RoomDatabaseConstructor<LocalCache> {
    override fun initialize(): LocalCache
}
