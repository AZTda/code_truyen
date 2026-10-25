package com.truyencloud.compose.screens.home

import com.truyencloud.compose.platform.PickedBookFile
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.api.UserFacingException
import com.truyencloud.shared.data.displayTitle
import com.truyencloud.shared.library.ImportedBook
import com.truyencloud.shared.library.LibraryShelves
import com.truyencloud.shared.repo.NovelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Nạp tệp sách cá nhân (TXT/EPUB/dán văn bản) lên máy chủ rồi thêm truyện vừa nạp vào tủ sách. */
internal class HomeImportController(
    private val novelApi: NovelApi?,
    private val novelRepository: NovelRepository,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<HomeUiState>
) {
    fun open() {
        state.update { it.copy(showImportDialog = true, importMessage = null, importMessageIsError = false) }
    }

    fun close() {
        // Đóng tay: lỗi (nếu có) đã hiện trong hộp thoại, không để nó treo ở Tủ sách
        state.update { it.copy(showImportDialog = false, importMessage = null, importMessageIsError = false) }
    }

    /** Tắt dải báo kết quả nạp tệp ở Tủ sách (lỗi đến sau khi đã đóng hộp thoại không tự biến mất). */
    fun dismissMessage() {
        clearJob?.cancel()
        state.update { it.copy(importMessage = null, importMessageIsError = false) }
    }

    fun importText(title: String, author: String, content: String) = launchImport(title, author) {
        requireApi().importNovelText(title, author, content)
    }

    fun importFile(title: String, author: String, file: PickedBookFile) = launchImport(title, author) {
        requireApi().importNovelFile(title, author, file.name, file.bytes, file.mimeType)
    }

    private fun requireApi(): NovelApi = novelApi ?: throw UserFacingException("Chưa kết nối được máy chủ để nạp tệp.")

    private var clearJob: Job? = null

    /** Thông báo thành công tự tắt sau vài giây (lỗi thì giữ trong hộp thoại cho tới khi đóng). */
    private fun scheduleClear(message: String) {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(SUCCESS_MESSAGE_MS)
            state.update { if (it.importMessage == message) it.copy(importMessage = null) else it }
        }
    }

    private fun launchImport(title: String, author: String, upload: suspend () -> String) {
        if (HomeMessages.isGuest()) {
            // Máy chủ chỉ nhận tệp từ tài khoản đã đăng nhập; báo ngay thay vì để khách nhận lỗi "hết phiên"
            state.update { it.copy(importMessage = HomeMessages.IMPORT_NEEDS_LOGIN, importMessageIsError = true) }
            return
        }
        scope.launch {
            state.update { it.copy(isImporting = true) }
            try {
                val body = upload()
                // Máy chủ chỉ lưu truyện trên kho của nó; phải tự thêm vào tủ thì Tủ sách mới có truyện này
                val book = ImportedBook.parse(body, fallbackTitle = title, fallbackAuthor = author)
                val added = book?.let { novelRepository.addToLibrary(it) }
                val label = added?.displayTitle ?: title
                val chapters = added?.totalChapters?.takeIf { it > 0 }?.let { " ($it chương)" }.orEmpty()
                // Dải báo thành công có biểu tượng dấu tích riêng nên câu chữ không cần thêm ký hiệu
                val message = if (book != null) "Đã nạp “$label”$chapters vào tủ sách." else "Đã nạp “$label” lên máy chủ."
                state.update {
                    it.copy(
                        isImporting = false,
                        showImportDialog = false,
                        selectedShelf = if (added != null) LibraryShelves.READING else it.selectedShelf,
                        importMessage = message,
                        importMessageIsError = false
                    )
                }
                scheduleClear(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update {
                    it.copy(isImporting = false, importMessage = "Không nạp được tệp. ${FriendlyErrors.of(e)}", importMessageIsError = true)
                }
            }
        }
    }

    private companion object {
        const val SUCCESS_MESSAGE_MS = 6_000L
    }
}
