package com.truyencloud.shared.library

import com.truyencloud.shared.api.ApiHttpException
import com.truyencloud.shared.api.DiscoverResponse

/**
 * Tải một bảng Khám phá và đổi thành [DiscoverResult]. Tách khỏi NovelRepository (NovelApi là lớp cứng, không giả được)
 * để kiểm thử được cách xử lý máy chủ chưa biết "for_you".
 */
object DiscoverFetch {
    private const val UNPROCESSABLE = 422

    /**
     * @param call lời gọi mạng: (giá trị `sort`, `limit`) → phản hồi.
     *
     * Máy chủ cũ (hoặc đang quay lui bản) từ chối `sort=for_you` bằng 422: khi đó trả bảng chung "Mới cập nhật" với
     * `personalized = false` thay vì ném lỗi — ứng dụng cập nhật trước máy chủ vẫn dùng được tab Khám phá.
     * Cờ `personalized` chỉ có nghĩa với bảng cá nhân hoá; bảng chung luôn là false.
     */
    suspend fun load(
        sort: DiscoverSort,
        limit: Int,
        call: suspend (apiValue: String, limit: Int) -> DiscoverResponse
    ): DiscoverResult {
        val response = try {
            call(sort.apiValue, limit)
        } catch (e: ApiHttpException) {
            if (!sort.isPersonal || e.statusCode != UNPROCESSABLE) throw e
            return DiscoverMapper.toResult(call(DiscoverSort.RECENT.apiValue, limit)).copy(personalized = false)
        }
        val result = DiscoverMapper.toResult(response)
        return if (sort.isPersonal) result else result.copy(personalized = false)
    }
}
