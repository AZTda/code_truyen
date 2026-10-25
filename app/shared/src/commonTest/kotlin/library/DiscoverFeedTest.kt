package com.truyencloud.shared.library

import com.truyencloud.shared.api.DiscoverRemoteItem
import com.truyencloud.shared.api.DiscoverResponse
import com.truyencloud.shared.data.Novel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverFeedTest {

    private fun item(
        id: String = "n1",
        title: String = "Tên",
        titleVi: String? = "Tên Việt",
        url: String = "https://x/n1",
        chapters: Int = 12,
        status: String? = "ongoing"
    ) = DiscoverRemoteItem(
        id = id, title = title, title_vi = titleVi, author = "Tác giả", cover_url = "https://x/c.jpg",
        total_chapters = chapters, source_id = "69shuba", source_url = url, description = "Mô tả", status = status
    )

    @Test
    fun mapsTheServerPayloadTheSameWayAsSearchResults() {
        val novels = DiscoverMapper.toNovels(DiscoverResponse(listOf(item()), total = 1))
        val n = novels.single()
        assertEquals("n1", n.id)
        assertEquals("Tên", n.title)
        assertEquals("Tên Việt", n.titleVi)
        assertEquals("Tác giả", n.author)
        assertEquals("https://x/n1", n.url, "url = địa chỉ nguồn, để trang chi tiết tự cào khi máy chủ chưa có")
        assertEquals("https://x/c.jpg", n.coverUrl)
        assertEquals("69shuba", n.sourceId)
        assertEquals(12, n.totalChapters)
        assertEquals("Mô tả", n.description)
        // Không mang theo dữ liệu của người đọc: truyện Khám phá chưa thuộc tủ sách
        assertEquals(0, n.lastReadChapter)
        assertFalse(n.isFavorite)
        assertEquals(0L, n.addedAt)
    }

    @Test
    fun fallsBackToSourceUrlWhenIdIsMissingAndToViTitleWhenTitleIsMissing() {
        val n = DiscoverMapper.toNovels(
            DiscoverResponse(listOf(item(id = " ", title = "", titleVi = "Chỉ có tên Việt", url = "https://x/abc")))
        ).single()
        assertEquals("https://x/abc", n.id)
        assertEquals("Chỉ có tên Việt", n.title)
    }

    @Test
    fun dropsEntriesWithoutIdentityOrTitleAndDuplicatesKeepingServerOrder() {
        val novels = DiscoverMapper.toNovels(
            DiscoverResponse(
                listOf(
                    item(id = "a", title = "A"),
                    item(id = "b", title = "", titleVi = null),
                    item(id = "", title = "No id", url = ""),
                    item(id = "a", title = "A lần hai"),
                    item(id = "c", title = "C")
                )
            )
        )
        assertEquals(listOf("a", "c"), novels.map { it.id })
        assertEquals("A", novels.first().title)
    }

    @Test
    fun blankStatusBecomesOngoingAndNegativeChaptersBecomeZero() {
        val n = DiscoverMapper.toNovels(
            DiscoverResponse(listOf(item(status = "", chapters = -4)))
        ).single()
        assertEquals("ongoing", n.status)
        assertEquals(0, n.totalChapters)
        assertEquals("completed", DiscoverMapper.toNovels(DiscoverResponse(listOf(item(status = "completed")))).single().status)
        assertTrue(DiscoverMapper.toNovels(DiscoverResponse()).isEmpty())
    }

    @Test
    fun decodesTheRealServerPayloadWithTheClientJsonSettings() {
        // Cùng cấu hình Json với TruyenCloudApi.httpClient
        val json = Json { isLenient = true; ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        val body = """{"novels":[
            {"id":"jjwxc_2157106","title":"波士顿爱情故事","title_vi":"Chuyện Tình Boston","author":"Như Tâm",
             "cover_url":"https://x/c.jpg","total_chapters":29,"source_id":"jjwxc","source_url":"https://www.jjwxc.net/onebook.php?novelid=2157106",
             "description":"","status":"","extra_field_from_future":true},
            {"id":"import_1","title":"Sách riêng","title_vi":null,"author":null,"cover_url":null,"total_chapters":null,
             "source_id":"69shuba","source_url":"","description":null,"status":null}
        ],"total":2}"""
        val novels = DiscoverMapper.toNovels(json.decodeFromString(DiscoverResponse.serializer(), body))
        assertEquals(listOf("jjwxc_2157106", "import_1"), novels.map { it.id })
        assertEquals("Chuyện Tình Boston", novels.first().titleVi)
        assertEquals("ongoing", novels.first().status)
        assertEquals("", novels.last().author)
        assertEquals(0, novels.last().totalChapters)
    }

    @Test
    fun sortsExposeTheServerParameterValues() {
        assertEquals(listOf("for_you", "recent", "popular"), DiscoverSort.entries.map { it.apiValue })
        assertEquals(listOf("Dành cho bạn", "Mới cập nhật", "Đọc nhiều"), DiscoverSort.entries.map { it.label })
        // Khoá bản lưu nay theo tài khoản (xem DiscoverCacheStoreTest); khoá kiểu cũ chỉ còn để dọn
        assertEquals("home_discover_cache_user_a|popular", HomePrefKeys.discoverCacheKey(DiscoverSort.POPULAR, "user_a"))
        assertEquals("home_discover_cache_guest|recent", HomePrefKeys.discoverCacheKey(DiscoverSort.RECENT, ""))
    }

    @Test
    fun cacheRoundTripKeepsTheFieldsThatMatter() {
        val novels = listOf(
            Novel(id = "a", title = "A", titleVi = "Ai", author = "x", url = "https://x/a", coverUrl = "https://x/a.jpg",
                sourceId = "piaotia", totalChapters = 99, description = "d".repeat(500), status = "completed"),
            Novel(id = "b", title = "B")
        )
        val snap = assertNotNull(DiscoverCache.decode(DiscoverCache.encode(novels, savedAt = 1234L)))
        assertEquals(1234L, snap.savedAt)
        assertEquals(listOf("a", "b"), snap.novels.map { it.id })
        val a = snap.novels.first()
        assertEquals("Ai", a.titleVi)
        assertEquals("https://x/a", a.url)
        assertEquals("piaotia", a.sourceId)
        assertEquals(99, a.totalChapters)
        assertEquals("completed", a.status)
        assertTrue(a.description.length < 500, "mô tả dài bị cắt để bản lưu nhỏ")
    }

    @Test
    fun cacheIsCappedAtTheMaximumNumberOfEntries() {
        val many = (1..100).map { Novel(id = "n$it", title = "T$it") }
        val snap = assertNotNull(DiscoverCache.decode(DiscoverCache.encode(many, 1L)))
        assertEquals(DiscoverCache.MAX_ENTRIES, snap.novels.size)
    }

    @Test
    fun corruptOrEmptyCacheDecodesToNullInsteadOfCrashing() {
        assertNull(DiscoverCache.decode(null))
        assertNull(DiscoverCache.decode(""))
        assertNull(DiscoverCache.decode("{không phải json"))
        assertNull(DiscoverCache.decode("""{"savedAt":5,"novels":[]}"""))
        assertNull(DiscoverCache.decode("""{"savedAt":5,"novels":[{"id":"","title":""}]}"""))
        // Khoá lạ từ phiên bản sau không làm hỏng bản đọc
        assertNotNull(DiscoverCache.decode("""{"savedAt":5,"future":1,"novels":[{"id":"a","title":"A"}]}"""))
    }

    @Test
    fun freshnessWindowIsFiveMinutesAndRejectsClockSkew() {
        val saved = 1_000_000L
        assertTrue(DiscoverPolicy.isFresh(saved, saved + 60_000))
        assertFalse(DiscoverPolicy.isFresh(saved, saved + DiscoverPolicy.FRESH_MS))
        assertFalse(DiscoverPolicy.isFresh(0L, saved))
        assertFalse(DiscoverPolicy.isFresh(saved, saved - 1), "đồng hồ máy bị lùi thì coi là cũ, tải lại")
    }
}
