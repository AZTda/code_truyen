package com.truyencloud.compose.screens.detail

import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.api.NovelDetailResponse
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.NovelRepository
import kotlinx.coroutines.CancellationException

/** Kết quả nạp từ máy chủ: phản hồi gốc, mục lục đã hợp nhất với dữ liệu máy, và metadata nguồn mới nhất. */
internal class RemoteDetail(
    val remote: NovelDetailResponse,
    val chapters: List<Chapter>,
    val source: Novel
)

/** Lấy thông tin + mục lục truyện từ máy chủ (Stale-While-Revalidate) và đổi sang mô hình của app. */
internal class NovelDetailRemoteLoader(
    private val novelApi: NovelApi,
    private val novelRepository: NovelRepository,
    private val chapterRepository: ChapterRepository
) {
    /** Nạp và hợp nhất, CHƯA ghi gì xuống Room. [base] là metadata nguồn hiện có (giữ trường máy chủ để trống). */
    suspend fun load(novel: Novel, base: Novel): RemoteDetail {
        val remote = fetch(novel)
        val merged = chapterRepository.mergeRemoteChapterMetadata(toChapters(remote), fallbackNovelId = novel.id)
        return RemoteDetail(remote, merged, mergeMetadata(base, remote))
    }

    /**
     * Ghi xuống Room: KHÔNG chèn truyện (chỉ làm mới thông tin nếu truyện đã ở trong tủ) và mục lục chỉ ghi
     * phần metadata — nội dung, bản dịch, vị trí cuộn không bao giờ bị chép đè.
     */
    suspend fun persist(detail: RemoteDetail) {
        novelRepository.refreshMetadataIfInLibrary(detail.source)
        chapterRepository.saveChapters(detail.chapters)
    }

    /**
     * Gọi chi tiết theo id (kèm URL nguồn để máy chủ không đoán nhầm nguồn); thiếu mục lục hoặc lỗi
     * thì cào lại theo URL nguồn. Id dạng URL chỉ cào được.
     */
    private suspend fun fetch(novel: Novel): NovelDetailResponse {
        if (novel.id.isNotEmpty() && !novel.id.startsWith("http")) {
            return try {
                val d = novelApi.getNovelDetail(novel.id, novel.url)
                if (d.chapters.isEmpty() && d.total_chapters > 0 && novel.url.isNotBlank()) {
                    try { novelApi.scrapeNovel(novel.url) } catch (e: CancellationException) { throw e } catch (_: Exception) { d }
                } else {
                    d
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (novel.url.isBlank()) throw e
                try {
                    novelApi.scrapeNovel(novel.url)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    throw e
                }
            }
        }
        if (novel.url.isNotBlank()) return novelApi.scrapeNovel(novel.url)
        throw Exception("Không tìm thấy liên kết truyện.")
    }

    private fun toChapters(remote: NovelDetailResponse): List<Chapter> = remote.chapters.map { ch ->
        Chapter(
            id = "${remote.id}_${ch.chapter_number}",
            novelId = remote.id,
            chapterNumber = ch.chapter_number,
            title = ch.title,
            titleVi = ch.title_vi.orEmpty(),
            url = ch.url.orEmpty()
        )
    }

    /** Metadata nguồn mới nhất; trường máy chủ để trống thì giữ giá trị đang có. */
    private fun mergeMetadata(base: Novel, remote: NovelDetailResponse): Novel = base.copy(
        id = remote.id,
        title = remote.title.ifBlank { base.title },
        titleVi = remote.title_vi.orEmpty().ifBlank { base.titleVi },
        author = remote.author.orEmpty().ifBlank { base.author },
        url = remote.source_url.ifBlank { base.url },
        coverUrl = remote.cover_url.orEmpty().ifBlank { base.coverUrl },
        sourceId = remote.source_id.ifBlank { base.sourceId },
        totalChapters = remote.total_chapters,
        description = remote.description.orEmpty().ifBlank { base.description },
        status = remote.status.ifBlank { base.status }
    )
}
