package com.truyencloud.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Nội dung từng nhóm gợi ý: tủ sách, "dành cho bạn", chống trùng, bỏ chip không dùng được. Giới hạn số lượng: SearchSuggestionBudgetTest. */
class SearchSuggestionBuilderTest {

    // ---- Tài khoản chưa có gì ----

    @Test
    fun anAccountWithNothingPersonalGetsTheWholeDefaultListUnderTheQuickHeading() {
        val sections = buildSuggestions()
        assertEquals(listOf(SearchSuggestionKind.DEFAULT), sections.kinds())
        assertEquals("Gợi ý tìm kiếm nhanh", sections.single().heading)
        assertEquals(SearchDefaults.QUICK_SUGGESTIONS, sections.single().chips)
    }

    @Test
    fun theDefaultListIsTheOneThatWasHardCodedInTheSearchScreen() {
        assertEquals(
            listOf(
                "Đấu Phá Thương Khung", "Già Thiên", "Vạn Cổ Đệ Nhất Thần", "Phàm Nhân Tu Tiên", "Quỷ Bí Chi Chủ",
                "Đại Phụng Đả Canh Nhân", "Kiếm Lai", "Tuyết Trung Hãn Đao Hành", "Khánh Dư Niên", "Đấu La Đại Lục"
            ),
            SearchDefaults.QUICK_SUGGESTIONS
        )
        assertEquals(10, SearchDefaults.QUICK_SUGGESTIONS.map { it.lowercase() }.toSet().size)
    }

    @Test
    fun noDefaultsAtAllMeansNoDefaultSection() {
        assertTrue(buildSuggestions(defaults = emptyList()).isEmpty())
        assertEquals(listOf(SearchSuggestionKind.RECENT), buildSuggestions(recent = listOf("Kiếm Lai"), defaults = emptyList()).kinds())
    }

    // ---- Thứ tự, tiêu đề ----

    @Test
    fun sectionsComeInTheDocumentedOrderWithTheirVietnameseHeadings() {
        val sections = buildSuggestions(
            recent = listOf("Đấu La"),
            library = listOf(suggestionBook("l1", title = "Sách trong tủ")),
            forYou = listOf(suggestionPick("f1"))
        )
        assertEquals(
            listOf(SearchSuggestionKind.RECENT, SearchSuggestionKind.LIBRARY, SearchSuggestionKind.FOR_YOU, SearchSuggestionKind.DEFAULT),
            sections.kinds()
        )
        assertEquals(
            listOf("Tìm gần đây", "Từ tủ sách của bạn", "Gợi ý cho bạn", "Gợi ý tìm kiếm nhanh"),
            sections.map { it.heading }
        )
        assertEquals(listOf("Đấu La"), sections.chipsOf(SearchSuggestionKind.RECENT))
        assertEquals(listOf("Sách trong tủ"), sections.chipsOf(SearchSuggestionKind.LIBRARY))
        assertEquals(listOf("Gợi ý f1"), sections.chipsOf(SearchSuggestionKind.FOR_YOU))
    }

    @Test
    fun emptySectionsAreLeftOut() {
        val sections = buildSuggestions(recent = listOf("Đấu La"))
        assertEquals(listOf(SearchSuggestionKind.RECENT, SearchSuggestionKind.DEFAULT), sections.kinds())
        assertTrue(sections.all { it.chips.isNotEmpty() })
    }

    // ---- Tủ sách ----

    @Test
    fun libraryChipsFollowLastReadAtNewestFirstPreferTheVietnameseTitleAndAddAuthorsAsPlainNames() {
        val library = listOf(
            suggestionBook("a", title = "Gốc A", author = "Tác Giả A", lastReadAt = 100),
            suggestionBook("b", title = "Gốc B", titleVi = "Tên Việt B", author = "Tác Giả B", lastReadAt = 300),
            suggestionBook("c", title = "Gốc C", author = "Tác Giả A", lastReadAt = 200)
        )
        assertEquals(
            listOf("Tên Việt B", "Tác Giả B", "Gốc C", "Tác Giả A", "Gốc A"),
            buildSuggestions(library = library).chipsOf(SearchSuggestionKind.LIBRARY),
            "B đọc gần nhất, rồi C, rồi A; tác giả A chỉ hiện một lần, ngay sau truyện gần nhất của họ"
        )
    }

    @Test
    fun libraryOrderDoesNotDependOnTheOrderTheLibraryWasHandedIn() {
        val a = suggestionBook("a", lastReadAt = 100)
        val b = suggestionBook("b", lastReadAt = 300)
        val c = suggestionBook("c", lastReadAt = 200)
        val expected = listOf("Truyện b", "Truyện c", "Truyện a")
        assertEquals(expected, buildSuggestions(library = listOf(a, b, c)).chipsOf(SearchSuggestionKind.LIBRARY))
        assertEquals(expected, buildSuggestions(library = listOf(c, a, b)).chipsOf(SearchSuggestionKind.LIBRARY))
        assertEquals(expected, buildSuggestions(library = listOf(b, c, a)).chipsOf(SearchSuggestionKind.LIBRARY))
    }

    @Test
    fun neverReadBooksComeAfterReadOnesNewestAddedFirst() {
        val library = listOf(
            suggestionBook("old", lastReadAt = 0, addedAt = 10),
            suggestionBook("read", lastReadAt = 5, addedAt = 1),
            suggestionBook("new", lastReadAt = 0, addedAt = 99)
        )
        assertEquals(listOf("Truyện read", "Truyện new", "Truyện old"), buildSuggestions(library = library).chipsOf(SearchSuggestionKind.LIBRARY))
    }

    @Test
    fun aBlankVietnameseTitleFallsBackToTheOriginalOne() {
        val library = listOf(suggestionBook("a", title = "那么远，那么近", titleVi = "  "))
        assertEquals(listOf("那么远，那么近"), buildSuggestions(library = library).chipsOf(SearchSuggestionKind.LIBRARY))
    }

    @Test
    fun placeholderAuthorsTheServerFillsInAreNeverSuggested() {
        val library = listOf(
            suggestionBook("1", author = "Unknown", lastReadAt = 9),
            suggestionBook("2", author = " khuyết danh ", lastReadAt = 8),
            suggestionBook("3", author = "UU看书", lastReadAt = 7),
            suggestionBook("4", author = "晋江文学城", lastReadAt = 6),
            suggestionBook("5", author = "Không rõ tác giả", lastReadAt = 5),
            suggestionBook("6", author = "", lastReadAt = 4),
            suggestionBook("7", author = "Ngã Cật Tây Hồng Thị", lastReadAt = 3)
        )
        val chips = buildSuggestions(library = library).chipsOf(SearchSuggestionKind.LIBRARY)
        assertTrue("Ngã Cật Tây Hồng Thị" in chips)
        listOf("Unknown", "khuyết danh", "UU看书", "晋江文学城", "Không rõ tác giả", "").forEach {
            assertFalse(chips.any { chip -> chip.equals(it.trim(), ignoreCase = true) }, "không được gợi ý \"$it\"")
        }
    }

    // ---- Dành cho bạn ----

    @Test
    fun forYouSkipsAnythingAlreadyInTheLibraryByIdUrlOrName() {
        val library = listOf(suggestionBook("n1", title = "Phàm Nhân Tu Tiên", url = "https://site.example/n1"))
        val forYou = listOf(
            suggestionPick("n1", title = "Cùng id"),
            suggestionPick("khac-id", title = "Cùng địa chỉ nguồn", url = "https://www.SITE.example/n1/"),
            suggestionPick("q", title = "phàm nhân tu tiên"),
            suggestionPick("ok", title = "Truyện Mới", reason = "Vì bạn đọc Phàm Nhân Tu Tiên")
        )
        assertEquals(listOf("Truyện Mới"), buildSuggestions(library = library, forYou = forYou).chipsOf(SearchSuggestionKind.FOR_YOU))
    }

    @Test
    fun forYouKeepsTheServerOrderUsesTheVietnameseTitleAndExposesReasonsAsHints() {
        val forYou = listOf(
            suggestionPick("b", title = "Gốc B", titleVi = "Việt B", reason = "Cùng tác giả"),
            suggestionPick("a", title = "Gốc A"),
            suggestionPick("c", title = "Gốc C", reason = "  ")
        )
        val section = buildSuggestions(forYou = forYou).first { it.kind == SearchSuggestionKind.FOR_YOU }
        assertEquals(listOf("Việt B", "Gốc A", "Gốc C"), section.chips)
        assertEquals(mapOf("Việt B" to "Cùng tác giả"), section.hints)
    }

    @Test
    fun onlySuggestionsThatAreShownKeepTheirHint() {
        val forYou = (1..30).map { suggestionPick("f$it", reason = "lý do $it") }
        val section = buildSuggestions(library = (1..30).map { suggestionBook("l$it") }, forYou = forYou)
            .first { it.kind == SearchSuggestionKind.FOR_YOU }
        assertEquals(6, section.chips.size, "30 gợi ý nhưng chỉ có chỗ cho phần chia của nhóm")
        assertEquals(section.chips.toSet(), section.hints.keys)
    }

    // ---- Trùng lặp ----

    @Test
    fun aChipAppearsOnlyOnceAcrossSectionsIgnoringCaseAndTheEarlierSectionKeepsIt() {
        val sections = buildSuggestions(
            recent = listOf("Kiếm Lai", "KIẾM LAI", "kiếm lai"),
            library = listOf(suggestionBook("l", title = "kiếm lai", author = "Phong Hỏa")),
            forYou = listOf(suggestionPick("f", title = "KIẾM LAI"), suggestionPick("g", title = "Phong hỏa"))
        )
        assertEquals(listOf("Kiếm Lai"), sections.chipsOf(SearchSuggestionKind.RECENT))
        assertEquals(listOf("Phong Hỏa"), sections.chipsOf(SearchSuggestionKind.LIBRARY))
        assertTrue(sections.chipsOf(SearchSuggestionKind.FOR_YOU).isEmpty())
        assertEquals(lowerKeys(sections.allChips()).toSet().size, sections.allChips().size, "không chip nào lặp lại")
    }

    @Test
    fun defaultsAlreadyShownAsPersonalChipsAreNotRepeated() {
        val sections = buildSuggestions(recent = listOf("kiếm lai", "ĐẤU LA ĐẠI LỤC"))
        val defaults = sections.chipsOf(SearchSuggestionKind.DEFAULT)
        assertFalse(defaults.any { it.equals("Kiếm Lai", ignoreCase = true) })
        assertFalse(defaults.any { it.equals("Đấu La Đại Lục", ignoreCase = true) })
        assertEquals(SearchDefaults.QUICK_SUGGESTIONS.size - 2, defaults.size)
        assertEquals(lowerKeys(sections.allChips()).toSet().size, sections.allChips().size)
    }


    // ---- Bỏ chip không dùng được ----

    @Test
    fun blankOverlongAndUrlLookingChipsAreDropped() {
        val long = "x".repeat(SearchSuggestionBuilder.MAX_CHIP_LENGTH + 1)
        val sections = buildSuggestions(
            recent = listOf("", "   ", "https://www.69shuba.com/book/1.htm", long, "  Đấu   La  "),
            library = listOf(suggestionBook("l1", title = long, titleVi = ""), suggestionBook("l2", title = "  ", titleVi = ""))
        )
        assertEquals(listOf("Đấu La"), sections.chipsOf(SearchSuggestionKind.RECENT))
        assertTrue(sections.chipsOf(SearchSuggestionKind.LIBRARY).isEmpty())
        assertTrue(sections.allChips().none { it.isBlank() || it.length > SearchSuggestionBuilder.MAX_CHIP_LENGTH })
    }
}
