package com.truyencloud.compose.screens.detail

import com.truyencloud.shared.data.ChapterFlags
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.NovelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hai bộ theo dõi Room của trang chi tiết: hàng trong tủ (tiến độ / kệ / yêu thích) và cờ nội dung
 * từng chương.
 *
 * Chỉ MỘT bộ thu cho mỗi loại, bám theo id truyện hiện tại bằng `flatMapLatest` — đổi truyện thì bộ cũ
 * tự bị huỷ. Bản cũ gọi lại `collect` ở mỗi lần nạp truyện mà không huỷ bộ trước nên bộ thu cộng dồn
 * mãi, và sau khi sang trình đọc chúng vẫn chạy lại truy vấn ở MỖI lần lưu vị trí cuộn.
 *
 * [pause] đặt id về rỗng để ngừng hẳn việc theo dõi khi màn hình không hiển thị.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class NovelDetailObservers(
    scope: CoroutineScope,
    novelRepository: NovelRepository,
    chapterRepository: ChapterRepository,
    onStored: (novelId: String, stored: Novel?) -> Unit,
    onFlags: (novelId: String, flags: List<ChapterFlags>) -> Unit
) {
    private val activeNovelId = MutableStateFlow("")

    init {
        scope.launch {
            activeNovelId
                .flatMapLatest { id ->
                    if (id.isBlank()) emptyFlow() else novelRepository.observeNovel(id).map { id to it }
                }
                .catch { it.printStackTrace() }
                .collect { (id, stored) -> onStored(id, stored) }
        }
        scope.launch {
            activeNovelId
                .flatMapLatest { id ->
                    if (id.isBlank()) emptyFlow() else chapterRepository.observeChapterFlags(id).map { id to it }
                }
                .catch { it.printStackTrace() }
                .collect { (id, flags) -> onFlags(id, flags) }
        }
    }

    fun watch(novelId: String) {
        activeNovelId.value = novelId
    }

    fun pause() {
        activeNovelId.value = ""
    }
}
