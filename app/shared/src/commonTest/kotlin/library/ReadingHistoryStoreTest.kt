package com.truyencloud.shared.library

import com.truyencloud.shared.api.DiscoverRemoteItem
import com.truyencloud.shared.api.DiscoverResponse
import com.truyencloud.shared.data.Novel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingHistoryStoreTest {

    @Test
    fun recordsAndDeduplicatesSameNovelAndChapterKeepingMostRecentFirst() {
        val prefs = FakeHomePrefs()
        val store = ReadingHistoryStore(prefs)
        store.clear()
        assertTrue(store.all().isEmpty())

        val n1 = Novel(id = "n1", title = "Tiên Nghịch", coverUrl = "https://x/c1.jpg", totalChapters = 2000)
        val n2 = Novel(id = "n2", title = "Phàm Nhân Tu Tiên", coverUrl = "https://x/c2.jpg", totalChapters = 2400)

        store.record(
            novel = n1,
            chapterNumber = 10,
            chapterTitle = "Chương 10: Ly biệt",
            isAudio = false,
            nowMs = 1000L
        )
        store.record(
            novel = n2,
            chapterNumber = 5,
            chapterTitle = "Chương 5: Thất Huyền Môn",
            isAudio = true,
            nowMs = 2000L
        )
        // Mở lại chương 10 của Tiên Nghịch ở chế độ audio -> nhảy lên đầu danh sách, không trùng bản ghi
        store.record(
            novel = n1,
            chapterNumber = 10,
            chapterTitle = "Chương 10: Ly biệt",
            isAudio = true,
            nowMs = 3000L
        )

        val list = store.all()
        assertEquals(2, list.size)
        assertEquals("n1", list.first().novelId)
        assertTrue(list.first().isAudio)
        assertEquals(3000L, list.first().timestampMs)
        assertEquals("n2", list.last().novelId)
    }

    @Test
    fun discoverMapperRecordsServerNovelMetaForTranslatedBadges() {
        val resp = DiscoverResponse(
            novels = listOf(
                DiscoverRemoteItem(
                    id = "novel_366",
                    title = "Bắc Cực Tinh",
                    title_vi = "Bắc Cực Tinh",
                    source_id = "69shuba",
                    source_url = "https://x/366",
                    total_chapters = 120,
                    translated_chapters = 48,
                    overnight_enabled = true
                )
            ),
            total = 1
        )
        val entries = DiscoverMapper.toEntries(resp)
        assertEquals(1, entries.size)
        val meta = ServerNovelMetaStore.get("novel_366")
        assertEquals(48, meta?.translatedChapters)
        assertEquals(true, meta?.overnightEnabled)
    }
}
