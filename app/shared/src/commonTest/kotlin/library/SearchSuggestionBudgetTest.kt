package com.truyencloud.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Số lượng chip: điền mặc định, trần 12, chia chỗ giữa các nhóm; và mỗi tài khoản một danh sách khác nhau. */
class SearchSuggestionBudgetTest {

    // ---- Điền mặc định ----

    @Test
    fun defaultsFillUpToTheLimitOnlyWhileFewerThanSixPersonalChips() {
        val few = buildSuggestions(recent = listOf("a1", "b2", "c3", "d4", "e5"))
        assertEquals(5, few.chipsOf(SearchSuggestionKind.RECENT).size)
        assertEquals(SearchDefaults.QUICK_SUGGESTIONS.take(7), few.chipsOf(SearchSuggestionKind.DEFAULT), "điền theo đúng thứ tự mặc định")
        assertEquals(SearchSuggestionBuilder.MAX_CHIPS, SearchSuggestionBuilder.chipCount(few))

        val enough = buildSuggestions(recent = listOf("a1", "b2", "c3", "d4", "e5", "f6"))
        assertFalse(SearchSuggestionKind.DEFAULT in enough.kinds(), "đủ 6 chip cá nhân thì không điền mặc định")
        assertEquals(6, SearchSuggestionBuilder.chipCount(enough))
    }

    @Test
    fun personalChipsFromEverySectionCountTowardsTheSix() {
        val sections = buildSuggestions(
            recent = listOf("a1", "b2"),
            library = listOf(suggestionBook("l1", author = "Tác Giả"), suggestionBook("l2")),
            forYou = listOf(suggestionPick("f1"))
        )
        // 2 gần đây + (tên, tác giả, tên) trong tủ + 1 gợi ý = 6
        assertEquals(6, sections.filter { it.kind != SearchSuggestionKind.DEFAULT }.sumOf { it.chips.size })
        assertFalse(SearchSuggestionKind.DEFAULT in sections.kinds())
    }

    @Test
    fun aShortDefaultListIsUsedAsIs() {
        val sections = buildSuggestions(recent = listOf("a1"), defaults = listOf("x1", "y2"))
        assertEquals(listOf("x1", "y2"), sections.chipsOf(SearchSuggestionKind.DEFAULT))
    }

    // ---- Giới hạn 12 chip ----

    @Test
    fun neverMoreThanTwelveChipsAndTheThreePersonalSectionsShareTheRoomEvenly() {
        val sections = buildSuggestions(
            recent = (1..12).map { "tìm $it" },
            library = (1..30).map { suggestionBook("l$it", lastReadAt = 1000L - it) },
            forYou = (1..30).map { suggestionPick("f$it") }
        )
        assertEquals(12, SearchSuggestionBuilder.chipCount(sections))
        assertEquals(
            listOf(4, 4, 4),
            listOf(SearchSuggestionKind.RECENT, SearchSuggestionKind.LIBRARY, SearchSuggestionKind.FOR_YOU).map { sections.chipsOf(it).size },
            "người tìm nhiều vẫn thấy cả tủ sách lẫn gợi ý cho bạn"
        )
        assertFalse(SearchSuggestionKind.DEFAULT in sections.kinds())
        // Mỗi nhóm giữ thứ tự riêng của nó
        assertEquals(listOf("tìm 1", "tìm 2", "tìm 3", "tìm 4"), sections.chipsOf(SearchSuggestionKind.RECENT))
        assertEquals(listOf("Truyện l1", "Truyện l2", "Truyện l3", "Truyện l4"), sections.chipsOf(SearchSuggestionKind.LIBRARY))
    }

    @Test
    fun aSectionWithLessThanItsShareLeavesTheRoomToTheOthers() {
        val sections = buildSuggestions(
            recent = listOf("a1", "b2"),
            library = (1..30).map { suggestionBook("l$it", lastReadAt = 1000L - it) },
            forYou = (1..30).map { suggestionPick("f$it") }
        )
        assertEquals(12, SearchSuggestionBuilder.chipCount(sections))
        assertEquals(2, sections.chipsOf(SearchSuggestionKind.RECENT).size)
        assertEquals(5, sections.chipsOf(SearchSuggestionKind.LIBRARY).size)
        assertEquals(5, sections.chipsOf(SearchSuggestionKind.FOR_YOU).size)
    }

    @Test
    fun aLoneSectionMayTakeTheWholeBudget() {
        val sections = buildSuggestions(library = (1..30).map { suggestionBook("l$it", lastReadAt = 1000L - it) })
        assertEquals(listOf(SearchSuggestionKind.LIBRARY), sections.kinds())
        assertEquals(12, sections.single().chips.size)
    }

    @Test
    fun overflowIsSharedStartingWithTheEarlierSection() {
        val sections = buildSuggestions(
            recent = (1..20).map { "tìm $it" },
            library = (1..20).map { suggestionBook("l$it", lastReadAt = 1000L - it) }
        )
        assertEquals(6, sections.chipsOf(SearchSuggestionKind.RECENT).size)
        assertEquals(6, sections.chipsOf(SearchSuggestionKind.LIBRARY).size)
    }


    // ---- Mỗi tài khoản một khác ----

    @Test
    fun twoAccountsWithDifferentLibrariesGetDifferentSuggestions() {
        val accountA = buildSuggestions(library = listOf(
            suggestionBook("a1", titleVi = "Phàm Nhân Tu Tiên", author = "Vong Ngữ", lastReadAt = 30),
            suggestionBook("a2", titleVi = "Quỷ Bí Chi Chủ", author = "Ái Tiềm Thủy Đích Ô Tặc", lastReadAt = 20)
        ))
        val accountB = buildSuggestions(library = listOf(
            suggestionBook("b1", titleVi = "Chuyện Tình Boston", author = "Như Tâm", lastReadAt = 30),
            suggestionBook("b2", titleVi = "Mạt Thế Sinh Tồn", author = "Lục Nguyệt", lastReadAt = 20)
        ))
        assertNotEquals(accountA, accountB)
        assertNotEquals(accountA.allChips(), accountB.allChips())
        assertEquals(
            listOf("Phàm Nhân Tu Tiên", "Vong Ngữ", "Quỷ Bí Chi Chủ", "Ái Tiềm Thủy Đích Ô Tặc"),
            accountA.chipsOf(SearchSuggestionKind.LIBRARY)
        )
        assertEquals(
            listOf("Chuyện Tình Boston", "Như Tâm", "Mạt Thế Sinh Tồn", "Lục Nguyệt"),
            accountB.chipsOf(SearchSuggestionKind.LIBRARY)
        )
        assertTrue("Vong Ngữ" !in accountB.allChips(), "tủ sách của A không lọt sang B")
    }

    @Test
    fun twoAccountsWithTheSameLibraryButDifferentRecommendationsOrHistoryDiffer() {
        val library = listOf(suggestionBook("l1", titleVi = "Kiếm Lai", lastReadAt = 5))
        val withPicks = buildSuggestions(library = library, forYou = listOf(suggestionPick("f1", title = "Mạt Thế Sinh Tồn")))
        val otherPicks = buildSuggestions(library = library, forYou = listOf(suggestionPick("f2", title = "Đại Chúa Tể")))
        val withHistory = buildSuggestions(recent = listOf("Tam Thể"), library = library)
        assertNotEquals(withPicks, otherPicks)
        assertNotEquals(withPicks, withHistory)
        assertNull(withHistory.firstOrNull { it.kind == SearchSuggestionKind.FOR_YOU })
    }

    @Test
    fun twoEmptyAccountsGetExactlyTheSameDefaults() {
        val first = buildSuggestions()
        val second = buildSuggestions(recent = emptyList(), library = emptyList(), forYou = emptyList())
        assertEquals(first, second)
        assertEquals(SearchDefaults.QUICK_SUGGESTIONS, first.allChips())
    }

    @Test
    fun theSameInputsAlwaysGiveTheSameOutput() {
        val library = (1..15).map { suggestionBook("l$it", author = "Tác giả ${it % 4}", lastReadAt = (it * 7 % 5).toLong()) }
        val forYou = (1..15).map { suggestionPick("f$it", reason = "r$it") }
        val recent = listOf("a1", "b2", "A1")
        assertEquals(buildSuggestions(recent, library, forYou), buildSuggestions(recent, library, forYou))
    }

    // ---- Cùng lịch sử tìm kiếm của kho ----

    @Test
    fun aSharedDeviceNeverCarriesOneAccountsHistoryIntoTheNextAccountsSuggestions() {
        var who = "user_a"
        val history = SearchHistoryStore(FakeHomePrefs(), ownerId = { who })
        history.add("Bí mật của A")
        history.add("Phàm Nhân Tu Tiên")
        val forA = buildSuggestions(recent = history.recent())
        assertEquals(listOf("Phàm Nhân Tu Tiên", "Bí mật của A"), forA.chipsOf(SearchSuggestionKind.RECENT))

        who = "user_b"
        val forB = buildSuggestions(recent = history.recent())
        assertTrue(forB.chipsOf(SearchSuggestionKind.RECENT).isEmpty())
        assertEquals(buildSuggestions(), forB, "B vừa đăng nhập, chưa có gì: chỉ có danh sách mặc định")
        assertTrue("Bí mật của A" !in forB.allChips())
    }
}
