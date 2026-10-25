package com.truyencloud.shared.reader

import com.truyencloud.shared.api.GlossaryServerTerm
import com.truyencloud.shared.data.TermMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlossaryMergeTest {

    private fun local(
        source: String,
        target: String,
        pinned: Boolean = false,
        updated: Long = 0,
        novel: String = "n1",
        hits: Int = 0
    ) = TermMemory(
        id = "$novel|$source", novelId = novel, source = source, target = target,
        pinned = pinned, updatedEpoch = updated, hitCount = hits
    )

    private fun server(source: String, target: String, auto: Boolean = true) = GlossaryServerTerm(source, target, auto)

    @Test
    fun localTermsComeFirstPinnedThenMostRecentlyEdited() {
        val merged = GlossaryMerge.merge(
            local = listOf(
                local("岑星", "Sầm Tinh", updated = 10),
                local("林动", "Lâm Động", updated = 30),
                local("萧炎", "Tiêu Viêm", pinned = true, updated = 5)
            ),
            server = emptyList()
        )
        assertEquals(listOf("萧炎", "林动", "岑星"), merged.map { it.source })
        assertTrue(merged.all { it.origin == GlossaryOrigin.LOCAL && it.editable })
        assertTrue(merged.first().pinned)
    }

    @Test
    fun serverTermsAreAddedWithTheirBadgeAndAreReadOnly() {
        val merged = GlossaryMerge.merge(
            local = listOf(local("林动", "Lâm Động")),
            server = listOf(server("岑星", "Sầm Tinh", auto = true), server("天玄宗", "Thiên Huyền Tông", auto = false))
        )
        assertEquals(listOf("林动", "天玄宗", "岑星"), merged.map { it.source }, "mục chung trước mục tự động")
        val auto = merged.first { it.source == "岑星" }
        assertEquals(GlossaryOrigin.SERVER_AUTO, auto.origin)
        assertTrue(auto.isAuto)
        assertFalse(auto.editable)
        assertNull(auto.localId)
        assertEquals(GlossaryOrigin.SERVER_SHARED, merged.first { it.source == "天玄宗" }.origin)
    }

    @Test
    fun theReadersOwnTermWinsOverTheServerOneWithTheSameSource() {
        val merged = GlossaryMerge.merge(
            local = listOf(local("岑星", "Sầm Tinh (của tôi)")),
            server = listOf(server("岑星", "Sâm Tinh"))
        )
        assertEquals(1, merged.size)
        val e = merged.single()
        assertEquals("Sầm Tinh (của tôi)", e.target)
        assertEquals(GlossaryOrigin.LOCAL, e.origin)
        assertTrue(e.overridesServer)
        assertEquals("Sâm Tinh", e.serverTarget)
    }

    @Test
    fun sameTargetAsTheServerIsNotReportedAsAnOverride() {
        val e = GlossaryMerge.merge(listOf(local("岑星", "Sầm Tinh")), listOf(server("岑星", "Sầm Tinh"))).single()
        assertFalse(e.overridesServer)
    }

    @Test
    fun serverGroupIsSortedLongSourceFirstThenAlphabetically() {
        val merged = GlossaryMerge.merge(
            local = emptyList(),
            server = listOf(
                server("麻省", "Ma Tỉnh"),
                server("麻省理工", "MIT"),
                server("阿", "A"),
                server("乙乙", "Ất Ất")
            )
        )
        assertEquals(listOf("麻省理工", "乙乙", "麻省", "阿"), merged.map { it.source })
    }

    @Test
    fun blankEntriesAndDuplicateServerSourcesAreDropped() {
        val merged = GlossaryMerge.merge(
            local = listOf(local("", "x"), local("岑星", " ")),
            server = listOf(server("", "x"), server("林动", ""), server("天", "Thiên"), server("天", "Trời"))
        )
        assertEquals(listOf("天"), merged.map { it.source })
    }

    @Test
    fun globalLocalTermsAreFlagged() {
        val merged = GlossaryMerge.merge(listOf(local("Harvard", "Harvard", novel = "")), emptyList())
        assertTrue(merged.single().global)
    }

    @Test
    fun filterIgnoresCaseAndDiacritics() {
        val entries = GlossaryMerge.merge(listOf(local("岑星", "Sầm Tinh"), local("林动", "Lâm Động")), emptyList())
        assertEquals(listOf("岑星"), GlossaryMerge.filter(entries, "sam tinh").map { it.source })
        assertEquals(listOf("林动"), GlossaryMerge.filter(entries, "林").map { it.source })
        assertEquals(2, GlossaryMerge.filter(entries, "  ").size)
        assertTrue(GlossaryMerge.filter(entries, "zzz").isEmpty())
    }

    // ── Kiểm tra mục ────────────────────────────────────────────────────────

    @Test
    fun validateExplainsWhyAnEntryIsUnusable() {
        assertNull(GlossaryTerms.validate("岑星", "Sầm Tinh"))
        assertEquals(GlossaryTerms.MSG_NEED_BOTH, GlossaryTerms.validate("", "Sầm Tinh"))
        assertEquals(GlossaryTerms.MSG_NEED_BOTH, GlossaryTerms.validate("岑星", "  "))
        assertEquals(GlossaryTerms.MSG_SAME, GlossaryTerms.validate("Harvard", "Harvard"))
        assertEquals(GlossaryTerms.MSG_SOURCE_TOO_LONG, GlossaryTerms.validate("字".repeat(33), "x"))
        assertEquals(GlossaryTerms.MSG_TARGET_TOO_LONG, GlossaryTerms.validate("岑星", "x".repeat(81)))
        assertEquals(GlossaryTerms.MSG_MULTILINE, GlossaryTerms.validate("岑\n星", "x"))
        assertNull(GlossaryTerms.validate("字".repeat(32), "x".repeat(80)), "đúng giới hạn thì vẫn hợp lệ")
    }

    @Test
    fun chineseDetection() {
        assertTrue(GlossaryTerms.containsCjk("岑星"))
        assertFalse(GlossaryTerms.containsCjk("Sầm Tinh"))
        assertTrue(GlossaryTerms.looksChinese("岑星站在山顶，望着远方的云海，心中思绪万千。"))
        assertFalse(GlossaryTerms.looksChinese("Sầm Tinh đứng trên đỉnh núi, nhìn biển mây phía xa."))
        assertFalse(GlossaryTerms.looksChinese("Bản dịch có nhắc tên 岑星 một lần giữa câu tiếng Việt rất dài."), "tên Hán lẫn trong bản dịch không phải bản gốc")
        assertFalse(GlossaryTerms.looksChinese(""))
    }

    @Test
    fun exactSourceIsOnlyTheTermSelectedInTheChineseOriginal() {
        assertEquals("岑星", GlossaryTerms.exactSource("  岑星 "))
        assertEquals("Sầm 岑星", GlossaryTerms.exactSource("Sầm 岑星"), "có chữ Hán là đủ: nguyên văn người đọc đã chọn")
        assertNull(GlossaryTerms.exactSource("Sầm Tinh"), "chọn từ bản dịch: khoá là chữ Hán AI đoán")
        assertNull(GlossaryTerms.exactSource("   "))
    }

    @Test
    fun rowKeysAreUniqueEvenWhenNovelAndGlobalTermsShareASource() {
        val entries = GlossaryMerge.merge(
            local = listOf(local("MIT", "Học viện MIT", novel = "n1"), local("MIT", "MIT", novel = "")),
            server = listOf(server("岑星", "Sầm Tinh"))
        )
        assertEquals(3, entries.size)
        assertEquals(3, entries.map { it.key }.toSet().size, "LazyColumn sập nếu hai dòng trùng khoá")
        assertEquals("server|岑星", entries.last().key)
    }

    @Test
    fun contextIsClippedAroundTheTerm() {
        val paragraph = "a".repeat(3000) + "岑星" + "b".repeat(3000)
        val clipped = GlossaryTerms.clipContext(paragraph, "岑星", max = 1200)
        assertEquals(1200, clipped.length)
        assertTrue("岑星" in clipped)
        assertEquals("ngắn", GlossaryTerms.clipContext("  ngắn  ", "x"))
        assertEquals(1200, GlossaryTerms.clipContext("z".repeat(5000), "không có", max = 1200).length)
    }
}
