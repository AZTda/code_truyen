package com.truyencloud.compose.screens.detail

import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.repo.NovelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Các thao tác tủ sách của trang chi tiết: thêm vào tủ, yêu thích, gỡ khỏi tủ.
 * Kết quả hiện ra qua Room (`observeNovel`) nên ở đây chỉ cần báo thông điệp ngắn cho người dùng.
 */
internal class NovelLibraryActions(
    private val novelRepository: NovelRepository,
    private val scope: CoroutineScope,
    private val update: ((NovelDetailUiState) -> NovelDetailUiState) -> Unit
) {
    /** Thêm vào tủ (kệ "đang đọc"). Mở trang chi tiết không tự thêm — phải bấm nút này. */
    fun add(novel: Novel) = launchAction("Đã thêm vào tủ sách.") { novelRepository.addToLibrary(novel) }

    /** Bật/tắt yêu thích; truyện chưa ở trong tủ sẽ được thêm vào tủ kèm yêu thích. */
    fun toggleFavorite(novel: Novel, wasInLibrary: Boolean) =
        launchAction(if (wasInLibrary) null else "Đã thêm vào tủ sách.") { novelRepository.toggleFavorite(novel) }

    fun moveToShelf(novelId: String, shelf: String) =
        launchAction("Đã chuyển sang kệ ${shelfLabel(shelf)}.") { novelRepository.updateShelf(novelId, shelf) }

    fun remove(novelId: String, deleteOfflineData: Boolean) =
        launchAction("Đã gỡ khỏi tủ sách.") { novelRepository.removeFromLibrary(novelId, deleteOfflineData) }

    private fun launchAction(successMessage: String?, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                if (successMessage != null) update { it.copy(libraryMessage = successMessage) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(libraryMessage = "Lỗi: không cập nhật được tủ sách (${e.message ?: "lỗi không rõ"}).") }
            }
        }
    }
}
