package com.truyencloud.shared.library

import com.truyencloud.shared.api.DiscoverRemoteItem
import com.truyencloud.shared.api.DiscoverResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lý do gợi ý và cờ cá nhân hoá của `/v1/novels/discover`: đọc được cả phản hồi cũ lẫn mới. */
class DiscoverReasonsTest {

    // Cùng cấu hình Json với TruyenCloudApi.httpClient
    private val clientJson = Json { isLenient = true; ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private fun item(id: String, title: String = "Truyện $id", reason: String? = null, url: String = "https://x/$id") =
        DiscoverRemoteItem(id = id, title = title, title_vi = "", source_id = "69shuba", source_url = url, reason = reason)

    @Test
    fun entriesKeepTheReasonNextToTheNovel() {
        val entries = DiscoverMapper.toEntries(
            DiscoverResponse(listOf(item("a", reason = "Vì bạn đã đọc Phàm Nhân Tu Tiên"), item("b"), item("c", reason = "")))
        )
        assertEquals(listOf("a", "b", "c"), entries.map { it.novel.id })
        assertEquals(listOf("Vì bạn đã đọc Phàm Nhân Tu Tiên", "", ""), entries.map { it.reason })
    }

    @Test
    fun entriesFollowExactlyTheSameRulesAsToNovels() {
        val response = DiscoverResponse(
            listOf(
                item("a", reason = "r1"),
                item("b", title = "", reason = "bị bỏ vì không có tên"),
                item("", title = "Không id", url = "", reason = "bị bỏ vì không có định danh"),
                item("a", title = "Trùng id", reason = "bị bỏ vì trùng"),
                item(" ", title = "Id lấy từ địa chỉ nguồn", url = "https://x/zzz", reason = "r2"),
                item("c")
            )
        )
        val entries = DiscoverMapper.toEntries(response)
        assertEquals(DiscoverMapper.toNovels(response), entries.map { it.novel })
        assertEquals(listOf("a", "https://x/zzz", "c"), entries.map { it.novel.id })
        assertEquals(listOf("r1", "r2", ""), entries.map { it.reason })
    }

    @Test
    fun theFirstOfTwoDuplicatesWinsTogetherWithItsReason() {
        val entries = DiscoverMapper.toEntries(DiscoverResponse(listOf(item("a", reason = "đầu"), item("a", reason = "sau"))))
        assertEquals("đầu", entries.single().reason)
    }

    @Test
    fun reasonsAreCleanedAndCappedSoACardNeverGrowsWithoutLimit() {
        val messy = DiscoverMapper.toEntries(DiscoverResponse(listOf(item("a", reason = "  Cùng\ntác giả \t với   X  "))))
        assertEquals("Cùng tác giả với X", messy.single().reason)

        val long = DiscoverMapper.toEntries(DiscoverResponse(listOf(item("a", reason = "x".repeat(500))))).single().reason
        assertEquals(DiscoverMapper.MAX_REASON, long.length)

        val blank = DiscoverMapper.toEntries(DiscoverResponse(listOf(item("a", reason = " \n\t "))))
        assertEquals("", blank.single().reason)
    }

    @Test
    fun resultCarriesThePersonalizedFlagAndDefaultsToAGeneralList() {
        assertFalse(DiscoverMapper.toResult(DiscoverResponse(listOf(item("a")))).personalized)
        val personal = DiscoverMapper.toResult(DiscoverResponse(listOf(item("a", reason = "r")), personalized = true))
        assertTrue(personal.personalized)
        assertEquals(listOf("a"), personal.novels.map { it.id })
        assertEquals("r", personal.entries.single().reason)
    }

    @Test
    fun anOldServerPayloadWithoutTheNewFieldsStillDecodes() {
        val body = """{"novels":[{"id":"n1","title":"Một","title_vi":"Một Việt","author":"A","cover_url":"","total_chapters":3,
            "source_id":"jjwxc","source_url":"https://x/n1","description":"","status":"ongoing"}],"total":1}"""
        val response = clientJson.decodeFromString(DiscoverResponse.serializer(), body)
        assertFalse(response.personalized)
        assertNull(response.novels.single().reason)
        val result = DiscoverMapper.toResult(response)
        assertEquals("", result.entries.single().reason)
        assertFalse(result.personalized)
    }

    @Test
    fun theNewPayloadDecodesReasonsFlagAndToleratesNullsAndUnknownKeys() {
        val body = """{"novels":[
            {"id":"n1","title":"Một","source_id":"jjwxc","source_url":"https://x/n1","reason":"Vì bạn đọc X","future_field":[1,2]},
            {"id":"n2","title":"Hai","source_id":"jjwxc","source_url":"https://x/n2","reason":null},
            {"id":"n3","title":"Ba","source_id":"jjwxc","source_url":"https://x/n3"}
        ],"total":3,"personalized":true,"algorithm":"v2"}"""
        val result = DiscoverMapper.toResult(clientJson.decodeFromString(DiscoverResponse.serializer(), body))
        assertTrue(result.personalized)
        assertEquals(listOf("Vì bạn đọc X", "", ""), result.entries.map { it.reason })
    }

    @Test
    fun aNullPersonalizedFlagIsTreatedAsFalse() {
        val response = clientJson.decodeFromString(DiscoverResponse.serializer(), """{"novels":[],"total":0,"personalized":null}""")
        assertFalse(response.personalized)
    }

    @Test
    fun reasonsByIdKeepsOnlyTheEntriesThatHaveOne() {
        val entries = DiscoverMapper.toEntries(DiscoverResponse(listOf(item("a", reason = "r"), item("b"), item("c", reason = "s"))))
        assertEquals(mapOf("a" to "r", "c" to "s"), entries.reasonsById())
    }
}
