package com.truyencloud.shared.library

import com.truyencloud.shared.prefs.PrefKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchHistoryStoreTest {

    private val prefs = FakeHomePrefs()
    private var owner = "user_a"
    private val history = SearchHistoryStore(prefs, ownerId = { owner })

    // ---- Ghi nhớ ----

    @Test
    fun startsEmptyAndRemembersNewestFirst() {
        assertTrue(history.recent().isEmpty())
        assertTrue(history.add("Kiếm Lai"))
        assertTrue(history.add("Phàm Nhân Tu Tiên"))
        assertTrue(history.add("Đấu La Đại Lục"))
        assertEquals(listOf("Đấu La Đại Lục", "Phàm Nhân Tu Tiên", "Kiếm Lai"), history.recent())
    }

    @Test
    fun trimsAndCollapsesWhitespace() {
        history.add("   Kiếm   Lai \n")
        history.add("那么远，那么近　 ")
        assertEquals(listOf("那么远，那么近", "Kiếm Lai"), history.recent())
    }

    @Test
    fun ignoresBlankQueries() {
        assertFalse(history.add(""))
        assertFalse(history.add("   "))
        assertFalse(history.add("\n\t "))
        assertTrue(history.recent().isEmpty())
        assertEquals(0, prefs.writes, "không ghi gì khi bỏ qua")
    }

    @Test
    fun ignoresUrlLookingQueries() {
        val urls = listOf(
            "https://www.69shuba.com/book/12345.htm",
            "HTTP://example.com",
            "  https://x.y/z  ",
            "www.jjwxc.net/onebook.php?novelid=1",
            "ftp://files.example/a.txt",
            "69shuba.com/book/12345.htm",
            "piaotia.com?id=3"
        )
        urls.forEach { assertFalse(history.add(it), it) }
        assertTrue(history.recent().isEmpty())
    }

    @Test
    fun keepsQueriesThatOnlyResembleAnAddressInPassing() {
        listOf("Vol.2 Kiếm Lai", "Mr.Nobody", "1.5/2 chương", "Tam quốc.演义", "example.com").forEach {
            assertTrue(history.add(it), it)
        }
        assertEquals(5, history.recent().size)
    }

    @Test
    fun ignoresPastedWallsOfText() {
        assertFalse(history.add("x".repeat(SearchHistoryStore.MAX_QUERY_LENGTH + 1)))
        assertTrue(history.add("x".repeat(SearchHistoryStore.MAX_QUERY_LENGTH)))
    }

    @Test
    fun aRepeatMovesToTheFrontIgnoringCaseAndKeepsTheLatestSpelling() {
        history.add("Kiếm Lai")
        history.add("Quỷ Bí Chi Chủ")
        history.add("Đấu La")
        history.add("kiếm lai")
        assertEquals(listOf("kiếm lai", "Đấu La", "Quỷ Bí Chi Chủ"), history.recent())
        history.add("  QUỶ BÍ CHI CHỦ ")
        assertEquals(listOf("QUỶ BÍ CHI CHỦ", "kiếm lai", "Đấu La"), history.recent())
    }

    @Test
    fun aRepeatThatOnlyChangesCaseNeverCostsAnOlderEntryItsSlot() {
        (1..12).forEach { history.add("truyện $it") }
        history.add("TRUYỆN 12")
        val recent = history.recent()
        assertEquals(12, recent.size)
        assertEquals("TRUYỆN 12", recent.first())
        assertTrue("truyện 1" in recent, "mục cũ nhất vẫn còn vì mục trùng được thay chứ không thêm")
        assertFalse("truyện 12" in recent)
    }

    @Test
    fun differentAccentsAreDifferentQueries() {
        history.add("Kiếm Lai")
        history.add("Kiem Lai")
        assertEquals(2, history.recent().size)
    }

    @Test
    fun isCappedAtTwelveDroppingTheOldest() {
        assertEquals(12, SearchHistoryStore.MAX_ENTRIES)
        (1..20).forEach { history.add("truyện $it") }
        val recent = history.recent()
        assertEquals(12, recent.size)
        assertEquals("truyện 20", recent.first())
        assertEquals("truyện 9", recent.last())
        // Một mục cũ được tìm lại thì nhảy lên đầu và đẩy mục cũ nhất ra
        history.add("truyện 9")
        assertEquals("truyện 9", history.recent().first())
        assertEquals(12, history.recent().size)
        history.add("mới tinh")
        assertEquals(12, history.recent().size)
        assertFalse("truyện 10" in history.recent())
    }

    @Test
    fun recentHonoursTheLimitAndNeverGoesNegative() {
        listOf("a1", "b2", "c3", "d4").forEach { history.add(it) }
        assertEquals(listOf("d4", "c3"), history.recent(2))
        assertTrue(history.recent(0).isEmpty())
        assertTrue(history.recent(-5).isEmpty())
        assertEquals(4, history.recent(50).size)
    }

    @Test
    fun clearForgetsEverything() {
        history.add("Kiếm Lai")
        history.clear()
        assertTrue(history.recent().isEmpty())
        assertNull(prefs.data[PrefKeys.SEARCH_HISTORY])
        history.add("Mới")
        assertEquals(listOf("Mới"), history.recent())
    }

    // ---- Bền vững & chủ sở hữu ----

    @Test
    fun survivesRestartsThroughTheStoredJson() {
        history.add("Kiếm Lai")
        history.add("Đấu La")
        val raw = assertNotNull(prefs.data[PrefKeys.SEARCH_HISTORY])
        assertTrue("user_a" in raw, "danh sách đi kèm id chủ sở hữu: $raw")
        val reopened = SearchHistoryStore(prefs, ownerId = { "user_a" })
        assertEquals(listOf("Đấu La", "Kiếm Lai"), reopened.recent())
    }

    @Test
    fun anotherAccountSeesNothingAndTheStaleListIsDropped() {
        history.add("Bí mật của A")
        owner = "user_b"
        assertTrue(history.recent().isEmpty(), "lịch sử của A không được hiện cho B")
        assertNull(prefs.data[PrefKeys.SEARCH_HISTORY], "bản của A bị xoá hẳn khỏi cài đặt")
        // A quay lại cũng không lấy lại được: bản đã bỏ
        owner = "user_a"
        assertTrue(history.recent().isEmpty())
    }

    @Test
    fun addingAsAnotherAccountStartsAFreshList() {
        history.add("Bí mật của A")
        owner = "user_b"
        history.add("Của B")
        assertEquals(listOf("Của B"), history.recent())
        owner = "user_a"
        assertTrue(history.recent().isEmpty())
    }

    @Test
    fun guestsHaveTheirOwnOwnerAndNeverShareWithAnAccount() {
        owner = ""
        history.add("Khách tìm")
        assertEquals(listOf("Khách tìm"), history.recent())
        owner = "guest"
        assertEquals(listOf("Khách tìm"), history.recent(), "rỗng và \"guest\" là cùng một khách")
        owner = "user_a"
        assertTrue(history.recent().isEmpty())
    }

    @Test
    fun readingOnlyEverRemovesNeverWritesBack() {
        history.add("Kiếm Lai")
        val writes = prefs.writes
        history.recent()
        history.recent()
        assertEquals(writes, prefs.writes)
    }

    @Test
    fun wipeRemovesTheListOfWhoeverOwnsIt() {
        history.add("Kiếm Lai")
        SearchHistoryStore.wipe(prefs)
        assertNull(prefs.data[PrefKeys.SEARCH_HISTORY])
        assertTrue(history.recent().isEmpty())
        SearchHistoryStore.wipe(null)
    }

    // ---- Dữ liệu hỏng / kho hỏng ----

    @Test
    fun corruptJsonIsTreatedAsEmptyAndRemoved() {
        prefs.data[PrefKeys.SEARCH_HISTORY] = "{không phải json"
        assertTrue(history.recent().isEmpty())
        assertNull(prefs.data[PrefKeys.SEARCH_HISTORY])
        assertTrue(history.add("Kiếm Lai"))
        assertEquals(listOf("Kiếm Lai"), history.recent())
    }

    @Test
    fun aHandEditedListIsSanitizedOnRead() {
        val junk = (listOf("", "  ", "https://x.y", "Kiếm Lai", "kiếm lai", "Đấu La") + (1..30).map { "q$it" })
            .joinToString(",") { "\"$it\"" }
        prefs.data[PrefKeys.SEARCH_HISTORY] = """{"owner":"user_a","queries":[$junk],"future":1}"""
        val recent = history.recent()
        assertEquals(12, recent.size)
        assertEquals(listOf("Kiếm Lai", "Đấu La", "q1"), recent.take(3))
    }

    @Test
    fun aBrokenStoreNeverCrashes() {
        history.add("Kiếm Lai")
        prefs.failure = IllegalStateException("kho hỏng")
        assertTrue(history.recent().isEmpty())
        history.add("Đấu La")
        history.clear()
    }

    @Test
    fun withoutAStoreItStillRemembersWithinTheSession() {
        var who = "user_a"
        val inMemory = SearchHistoryStore(null, ownerId = { who })
        inMemory.add("Kiếm Lai")
        inMemory.add("Đấu La")
        assertEquals(listOf("Đấu La", "Kiếm Lai"), inMemory.recent())
        who = "user_b"
        assertTrue(inMemory.recent().isEmpty())
    }

    @Test
    fun aCustomCapacityIsRespected() {
        val small = SearchHistoryStore(FakeHomePrefs(), ownerId = { "u" }, maxEntries = 3)
        listOf("a1", "b2", "c3", "d4").forEach { small.add(it) }
        assertEquals(listOf("d4", "c3", "b2"), small.recent())
    }
}
