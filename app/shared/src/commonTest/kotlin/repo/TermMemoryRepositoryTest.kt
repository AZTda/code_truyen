package com.truyencloud.shared.repo

import com.truyencloud.shared.api.GlossaryRemote
import com.truyencloud.shared.api.GlossaryServerTerm
import com.truyencloud.shared.api.LoreExplainResponse
import com.truyencloud.shared.data.TermMemory
import com.truyencloud.shared.data.TermMemoryDao
import com.truyencloud.shared.data.runNow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeTermMemoryDao : TermMemoryDao {
    val rows = LinkedHashMap<String, TermMemory>()

    override suspend fun getGlossaryFor(novelId: String, limit: Int): List<TermMemory> =
        rows.values.filter { it.novelId == novelId || it.novelId == "" }.take(limit)

    override fun observeForNovel(novelId: String): Flow<List<TermMemory>> =
        flowOf(rows.values.filter { it.novelId == novelId || it.novelId == "" })

    override suspend fun find(novelId: String, source: String): TermMemory? =
        rows.values.firstOrNull { it.novelId == novelId && it.source == source }

    override suspend fun findIncludingGlobal(novelId: String, source: String): TermMemory? =
        rows.values.firstOrNull { (it.novelId == novelId || it.novelId == "") && it.source == source }

    override suspend fun upsert(term: TermMemory) { rows[term.id] = term }
    override suspend fun upsertAll(terms: List<TermMemory>) { terms.forEach { rows[it.id] = it } }
    override suspend fun setPinned(id: String, pinned: Boolean, now: Long) { rows[id]?.let { rows[id] = it.copy(pinned = pinned) } }
    override suspend fun updateTarget(id: String, target: String, now: Long) { rows[id]?.let { rows[id] = it.copy(target = target) } }
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun deleteAllForNovel(novelId: String) { rows.values.removeAll { it.novelId == novelId } }
    override suspend fun countForNovel(novelId: String): Int = rows.values.count { it.novelId == novelId || it.novelId == "" }
}

private class FakeGlossaryRemote(var terms: List<GlossaryServerTerm> = emptyList(), var failure: Exception? = null) : GlossaryRemote {
    var calls = 0
    override suspend fun fetchTerms(novelId: String): List<GlossaryServerTerm> {
        calls++
        failure?.let { throw it }
        return terms
    }
}

class TermMemoryRepositoryTest {
    private val dao = FakeTermMemoryDao()
    private val remote = FakeGlossaryRemote()
    private val repo = TermMemoryRepository(dao, remote)

    private fun lore(
        term: String = "岑星",
        hanzi: String = "岑星",
        sino: String = "Sầm Tinh",
        fallback: Boolean = false,
        definition: String = "Tên nhân vật."
    ) = LoreExplainResponse(term = term, hanzi = hanzi, sino_vietnamese = sino, definition = definition, is_fallback = fallback)

    // ── Không bao giờ lưu kết quả dự phòng ──────────────────────────────────

    @Test
    fun fallbackLoreIsNeverSaved() {
        val saved = runNow { repo.saveFromLore("n1", lore(fallback = true)) }
        assertNull(saved)
        assertTrue(dao.rows.isEmpty(), "định nghĩa dự phòng không được vào trí nhớ thuật ngữ")
    }

    @Test
    fun fallbackIsRejectedEvenWithTheReadersOwnTarget() {
        val saved = runNow { repo.saveFromLore("n1", lore(fallback = true), preferredTarget = "Sầm Tinh", sourceOverride = "岑星") }
        assertNull(saved)
        assertTrue(dao.rows.isEmpty())
    }

    // ── Nguyên văn đã chọn trong bản gốc thắng chữ Hán AI đoán ─────────────

    @Test
    fun theExactSelectedSourceBecomesTheKeyInsteadOfTheGuessedHanzi() {
        // AI trả chữ giản thể / dài hơn văn bản thật: nếu dùng nó làm khoá thì bảng không bao giờ khớp chương
        val res = lore(term = "岑星", hanzi = "岑星君")
        val saved = assertNotNull(runNow { repo.saveFromLore("n1", res, sourceOverride = "岑星") })
        assertEquals("岑星", saved.source)
        assertEquals("n1|岑星", saved.id)
        assertEquals("Sầm Tinh", saved.target)
        assertEquals("岑星君", saved.hanzi, "dữ liệu tra cứu vẫn được giữ")
    }

    @Test
    fun withoutAnOverrideTheGuessedHanziIsStillUsed() {
        val saved = assertNotNull(runNow { repo.saveFromLore("n1", lore(term = "Sầm Tinh", hanzi = "岑星")) })
        assertEquals("岑星", saved.source)
    }

    @Test
    fun blankOverrideFallsBackToTheLoreSource() {
        val saved = assertNotNull(runNow { repo.saveFromLore("n1", lore(), sourceOverride = "   ") })
        assertEquals("岑星", saved.source)
    }

    @Test
    fun readersTargetBeatsTheSinoVietnamese() {
        val saved = assertNotNull(runNow { repo.saveFromLore("n1", lore(), preferredTarget = "Sầm Tinh Quân", pinned = true) })
        assertEquals("Sầm Tinh Quân", saved.target)
        assertTrue(saved.pinned)
    }

    @Test
    fun savingTwiceKeepsOneRowAndCountsTheLookups() {
        runNow { repo.saveFromLore("n1", lore(), pinned = true) }
        val second = assertNotNull(runNow { repo.saveFromLore("n1", lore(), preferredTarget = "Sầm Tinh 2") })
        assertEquals(1, dao.rows.size)
        assertEquals(2, second.hitCount)
        assertTrue(second.pinned, "đã ghim thì lần lưu sau không làm mất ghim")
        assertEquals("Sầm Tinh 2", second.target)
    }

    @Test
    fun unusableEntriesAreRejected() {
        assertNull(runNow { repo.saveFromLore("n1", lore(hanzi = "Sầm Tinh", term = "Sầm Tinh")) }, "tự dịch thành chính nó")
        assertNull(runNow { repo.saveFromLore("n1", lore(hanzi = "", term = "", sino = "")) })
        assertNull(runNow { repo.saveFromLore("n1", lore(), sourceOverride = "字".repeat(40)) }, "máy chủ bỏ im lặng mục quá dài")
        assertTrue(dao.rows.isEmpty())
    }

    // ── Thêm tay ────────────────────────────────────────────────────────────

    @Test
    fun manualEntryIsStoredAndKeepsEarlierLoreData() {
        runNow { repo.saveFromLore("n1", lore(), pinned = true) }
        val manual = assertNotNull(runNow { repo.addManual("n1", " 岑星 ", " Sầm Tinh Đại Nhân ") })
        assertEquals("Sầm Tinh Đại Nhân", manual.target)
        assertEquals("Tên nhân vật.", manual.definition, "ghi đè cách dịch không làm mất phần tra cứu")
        assertTrue(manual.pinned)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun manualEntryForAServerTermCreatesALocalOverride() {
        val manual = assertNotNull(runNow { repo.addManual("n1", "天玄宗", "Thiên Huyền Tông") })
        assertEquals("天玄宗", manual.hanzi, "nguồn chữ Hán được ghi nhận làm hanzi")
        assertEquals("n1", manual.novelId)
    }

    @Test
    fun manualGlobalEntryHasNoNovel() {
        val manual = assertNotNull(runNow { repo.addManual("n1", "MIT", "Học viện MIT", global = true) })
        assertEquals("", manual.novelId)
    }

    @Test
    fun invalidManualEntriesAreRefused() {
        assertNull(runNow { repo.addManual("n1", "", "x") })
        assertNull(runNow { repo.addManual("n1", "A", "A") })
        assertNull(runNow { repo.addManual("n1", "A", "x".repeat(200)) })
        assertTrue(dao.rows.isEmpty())
    }

    // ── Bảng máy chủ ────────────────────────────────────────────────────────

    @Test
    fun serverTermsComeFromTheRemote() {
        remote.terms = listOf(GlossaryServerTerm("岑星", "Sầm Tinh", auto = true))
        assertEquals(remote.terms, runNow { repo.serverTerms("n1") })
        assertEquals(1, remote.calls)
    }

    @Test
    fun serverFailureIsRaisedToTheCaller() {
        remote.failure = IllegalStateException("offline")
        var raised = false
        try {
            runNow { repo.serverTerms("n1") }
        } catch (_: IllegalStateException) {
            raised = true
        }
        assertTrue(raised, "giao diện tự quyết định hiện bảng trên máy khi máy chủ không gọi được")
    }

    // ── Bảng gửi kèm khi dịch không đổi ─────────────────────────────────────

    @Test
    fun glossarySentToTheTranslatorStillOrdersLongSourcesFirst() {
        runNow {
            repo.addManual("n1", "麻省", "Ma Tỉnh")
            repo.addManual("n1", "麻省理工", "MIT")
        }
        val sent = runNow { repo.glossaryFor("n1") }
        assertEquals(listOf("麻省理工", "麻省"), sent.map { it.source })
    }
}
