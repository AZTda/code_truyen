package com.truyencloud.shared.library

import com.truyencloud.shared.api.ApiHttpException
import com.truyencloud.shared.api.DiscoverRemoteItem
import com.truyencloud.shared.api.DiscoverResponse
import com.truyencloud.shared.data.runNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cách NovelRepository.discoverEntries gọi máy chủ: giá trị `sort`, cờ cá nhân hoá, và máy chủ cũ chưa biết "for_you". */
class DiscoverFetchTest {

    private fun item(id: String, reason: String? = null) =
        DiscoverRemoteItem(id = id, title = "Truyện $id", source_id = "69shuba", source_url = "https://x/$id", reason = reason)

    private class Server(private val answers: Map<String, () -> DiscoverResponse>) {
        val calls = mutableListOf<Pair<String, Int>>()
        suspend fun call(sort: String, limit: Int): DiscoverResponse {
            calls += sort to limit
            return (answers[sort] ?: error("không giả lập sort=$sort"))()
        }
    }

    private fun load(sort: DiscoverSort, server: Server, limit: Int = 30) =
        runNow { DiscoverFetch.load(sort, limit, server::call) }

    @Test
    fun forYouSendsTheApiValueAndKeepsReasonsAndTheFlag() {
        val server = Server(mapOf("for_you" to { DiscoverResponse(listOf(item("a", "Vì bạn đọc X"), item("b")), total = 2, personalized = true) }))
        val result = load(DiscoverSort.FOR_YOU, server, limit = 20)
        assertEquals(listOf("for_you" to 20), server.calls)
        assertTrue(result.personalized)
        assertEquals(listOf("a", "b"), result.novels.map { it.id })
        assertEquals(listOf("Vì bạn đọc X", ""), result.entries.map { it.reason })
    }

    @Test
    fun aGeneralAnswerToForYouIsReportedAsNotPersonalized() {
        // Khách / tủ sách quá ít: máy chủ trả bảng chung, personalized = false (hoặc không gửi)
        val server = Server(mapOf("for_you" to { DiscoverResponse(listOf(item("a"))) }))
        val result = load(DiscoverSort.FOR_YOU, server)
        assertFalse(result.personalized)
        assertEquals(listOf("a"), result.novels.map { it.id })
    }

    @Test
    fun theGeneralBoardsAreNeverMarkedPersonalEvenIfTheServerSaysSo() {
        for (sort in listOf(DiscoverSort.RECENT, DiscoverSort.POPULAR)) {
            val server = Server(mapOf(sort.apiValue to { DiscoverResponse(listOf(item("a")), personalized = true) }))
            val result = load(sort, server)
            assertEquals(listOf(sort.apiValue to 30), server.calls)
            assertFalse(result.personalized, sort.apiValue)
        }
    }

    @Test
    fun anOlderServerThatRejectsForYouFallsBackToTheGeneralRecentList() {
        val server = Server(
            mapOf(
                "for_you" to { throw ApiHttpException(422, "Tải danh sách khám phá thất bại (mã 422)") },
                "recent" to { DiscoverResponse(listOf(item("r1"), item("r2"))) }
            )
        )
        val result = load(DiscoverSort.FOR_YOU, server, limit = 25)
        assertEquals(listOf("for_you" to 25, "recent" to 25), server.calls)
        assertFalse(result.personalized)
        assertEquals(listOf("r1", "r2"), result.novels.map { it.id })
    }

    @Test
    fun otherFailuresOfForYouAreNotHiddenByTheFallback() {
        for (code in listOf(401, 429, 500, 503)) {
            val server = Server(mapOf("for_you" to { throw ApiHttpException(code, "lỗi $code") }))
            val e = assertFailsWith<ApiHttpException> { load(DiscoverSort.FOR_YOU, server) }
            assertEquals(code, e.statusCode)
            assertEquals(1, server.calls.size, "không gọi lại bảng khác khi gặp $code")
        }
        val offline = Server(mapOf("for_you" to { throw IllegalStateException("mất mạng") }))
        assertFailsWith<IllegalStateException> { load(DiscoverSort.FOR_YOU, offline) }
    }

    @Test
    fun a422OnAGeneralBoardIsARealErrorNotSomethingToWorkAround() {
        val server = Server(mapOf("popular" to { throw ApiHttpException(422, "sai") }))
        assertFailsWith<ApiHttpException> { load(DiscoverSort.POPULAR, server) }
        assertEquals(1, server.calls.size)
    }
}
