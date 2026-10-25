package com.truyencloud.shared.api

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

/** Một mục trong bảng thuật ngữ máy chủ đang áp cho truyện. */
@Serializable
data class GlossaryServerTerm(
    val source: String,
    val target: String,
    /** true: tên riêng AI tự trích sau khi dịch chương; false: do quản trị viên đặt. */
    val auto: Boolean = false
)

@Serializable
data class GlossaryServerResponse(
    val terms: List<GlossaryServerTerm> = emptyList()
)

/** Phía máy chủ của bảng thuật ngữ — tách thành giao diện để kiểm thử kho thuật ngữ mà không cần mạng. */
interface GlossaryRemote {
    suspend fun fetchTerms(novelId: String): List<GlossaryServerTerm>
}

class GlossaryApi : GlossaryRemote {
    private val client = TruyenCloudApi.httpClient
    private val base: String get() = TruyenCloudApi.baseUrl

    /** `GET /v1/novels/{id}/glossary` (đăng nhập hay không đều gọi được). */
    override suspend fun fetchTerms(novelId: String): List<GlossaryServerTerm> {
        val resp = client.get("$base/v1/novels/${novelId.encodeURLPathPart()}/glossary")
        resp.requireSuccess("Tải bảng thuật ngữ")
        return resp.body<GlossaryServerResponse>().terms
    }
}
