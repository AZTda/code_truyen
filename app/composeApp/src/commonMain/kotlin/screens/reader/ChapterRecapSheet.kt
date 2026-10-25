package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.shared.api.ChapterRecapResponse
import com.truyencloud.shared.reader.AiErrors
import com.truyencloud.shared.reader.RecapPolicy

/**
 * Bảng "Tóm tắt trước khi đọc". Chỉ hiện bản tóm tắt khi máy chủ thật sự tóm tắt được ([recap] đã qua RecapPolicy);
 * chương đầu / chưa có chương trước thì báo [nothingToRecap], AI hỏng thì báo [error] kèm nút thử lại — không bịa tóm tắt.
 */
@Composable
fun ChapterRecapSheet(
    chapterTitle: String,
    recap: ChapterRecapResponse?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    nothingToRecap: Boolean = false,
    onRetry: () -> Unit = {}
) {
    ReaderSheet(
        onDismiss = onDismiss,
        title = "Tóm tắt trước khi đọc",
        subtitle = chapterTitle,
        serifSubtitle = true,
        modifier = modifier
    ) {
        Box(modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = ReaderSheetInset)) {
            when {
                isLoading -> AiLoadingPanel("AI đang trích xuất bối cảnh chương trước…")
                error != null -> AiErrorPanel(
                    title = RecapPolicy.TITLE_FAILED,
                    detail = error,
                    // Khách chưa đăng nhập: thử lại không bao giờ thành công
                    onRetry = if (error == AiErrors.MSG_LOGIN_REQUIRED) null else onRetry,
                    onClose = onDismiss
                )
                nothingToRecap -> AiInfoPanel(RecapPolicy.NOTHING_TO_RECAP, onClose = onDismiss)
                recap != null -> ChapterRecapContent(recap = recap, onDismiss = onDismiss)
            }
        }
    }
}
