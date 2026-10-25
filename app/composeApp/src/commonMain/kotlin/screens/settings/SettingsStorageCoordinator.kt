package com.truyencloud.compose.screens.settings

import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.data.LocalDataMaintenance
import com.truyencloud.shared.repo.ChapterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SettingsStorageCoordinator(
    private val chapterRepository: ChapterRepository?,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val scope: CoroutineScope,
    maintenance: LocalDataMaintenance? = null
) {
    val cleaner: OfflineStorageCleaner? = chapterRepository?.let { OfflineStorageCleaner(it, maintenance) }

    /**
     * Bước 1 của "Dọn dẹp": KHÔNG xoá ngay mà mở hộp thoại xác nhận nêu rõ dung lượng, số chương và
     * cảnh báo bản dịch AI phải dịch lại. Trước đây nút này xoá sạch mọi chương đã tải mà không hỏi.
     */
    fun requestClearAll() {
        val c = cleaner
        if (c == null) {
            uiState.value = uiState.value.copy(cacheSize = "Không khả dụng", isCacheCleared = false)
            return
        }
        scope.launch {
            try {
                val summary = c.summary()
                uiState.value = if (summary.chapterCount == 0) {
                    // Không có gì để xoá thì đừng bắt người dùng xác nhận một thao tác rỗng
                    uiState.value.copy(cacheMessage = "Chưa có chương nào được tải về máy.", isCacheCleared = false)
                } else {
                    uiState.value.copy(
                        storageConfirm = StorageConfirm(ClearScope.ALL, chapterCount = summary.chapterCount, bytes = summary.bytes)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(cacheMessage = "Không đọc được dung lượng: ${FriendlyErrors.of(e)}")
            }
        }
    }

    fun dismissConfirm() {
        uiState.value = uiState.value.copy(storageConfirm = null)
    }

    /** Bước 2: người dùng đã xác nhận. */
    fun confirmClear() {
        val c = cleaner ?: return
        val confirm = uiState.value.storageConfirm ?: return
        uiState.value = uiState.value.copy(storageConfirm = null, isClearingStorage = true)
        scope.launch {
            try {
                val result = if (confirm.scope == ClearScope.ALL) c.clearAll() else c.clearNovel(confirm.novelId.orEmpty())
                uiState.value = uiState.value.copy(
                    isClearingStorage = false,
                    isCacheCleared = true,
                    cacheMessage = result.message(confirm.novelTitle)
                )
                refreshCacheSize(keepMessage = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    isClearingStorage = false,
                    isCacheCleared = false,
                    cacheMessage = "Không xoá được: ${FriendlyErrors.of(e)}"
                )
            }
        }
    }

    /**
     * @param keepMessage giữ câu "Đã xoá ... giải phóng ..." vừa hiện sau khi xoá. Các lần nạp lại khác (mở Cài đặt,
     * đóng Quản lý bộ nhớ) bỏ câu cũ đi, nếu không nó đứng mãi dù người dùng đã tải thêm chương mới.
     */
    fun refreshCacheSize(keepMessage: Boolean = false) {
        val c = cleaner ?: return
        scope.launch {
            try {
                val summary = c.summary()
                val current = uiState.value
                uiState.value = current.copy(
                    cacheSize = formatStorageBytes(summary.bytes),
                    cacheChapterCount = summary.chapterCount,
                    cacheMessage = if (keepMessage) current.cacheMessage else "",
                    isCacheCleared = keepMessage && current.isCacheCleared
                )
            } catch (_: Exception) {
            }
        }
    }

    fun openExportSheet() {
        uiState.value = uiState.value.copy(isExportSheetOpen = true)
    }

    fun closeExportSheet() {
        uiState.value = uiState.value.copy(isExportSheetOpen = false)
    }

    fun openStorageManager() {
        uiState.value = uiState.value.copy(isStorageManagerOpen = true)
    }

    fun closeStorageManager() {
        uiState.value = uiState.value.copy(isStorageManagerOpen = false)
        refreshCacheSize()
    }
}
