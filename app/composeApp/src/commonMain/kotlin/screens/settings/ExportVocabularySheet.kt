package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.export.PlatformShareHandler
import com.truyencloud.shared.export.VocabularyExporter
import com.truyencloud.shared.export.VocabularyExporter.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ExportMessage(val text: String, val tone: NoticeTone)

/**
 * Tờ trượt xuất từ vựng — 4 định dạng: CSV, Anki, JSON, Markdown. Mỗi dòng xuất ngay khi chạm; kết quả hiện ở dải
 * thông báo phía dưới (có biểu tượng, không chỉ dựa vào màu).
 */
@Composable
fun ExportVocabularySheet(
    isOpen: Boolean,
    savedWordDao: SavedWordDao?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    val colors = LocalCustomColors.current
    val scope = rememberCoroutineScope()
    var exportingFormat by remember { mutableStateOf<Format?>(null) }
    var message by remember { mutableStateOf<ExportMessage?>(null) }
    var wordCount by remember { mutableStateOf(0) }

    LaunchedEffect(isOpen) {
        if (isOpen && savedWordDao != null) {
            withContext(Dispatchers.Default) {
                wordCount = savedWordDao.getAllWordsOnce().size
            }
        }
    }

    IosSheet(onDismissRequest = onDismiss, modifier = modifier, title = "Xuất từ vựng") {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                text = "$wordCount từ đã lưu",
                style = AppText.secondary,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            Format.entries.forEach { format ->
                val busy = exportingFormat == format
                InsetRow(
                    title = format.label,
                    subtitle = describe(format),
                    icon = iconOf(format),
                    iconTint = InsetIconTint.Neutral,
                    onClick = if (exportingFormat != null) null else {
                        {
                            if (savedWordDao != null) {
                                exportingFormat = format
                                message = null
                                scope.launch {
                                    message = export(savedWordDao, format)
                                    exportingFormat = null
                                }
                            }
                        }
                    },
                    trailing = {
                        if (busy) RowSpinner()
                        else Icon(TruyenCloudIcons.Export, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.padding(start = 4.dp))
                    }
                )
            }

            message?.let {
                NoticeBanner(
                    text = it.text,
                    tone = it.tone,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            TextActionButton(
                text = "Đóng",
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

/** Đọc toàn bộ từ, dựng tệp rồi mở bảng chia sẻ của hệ điều hành. */
private suspend fun export(dao: SavedWordDao, format: Format): ExportMessage {
    return try {
        val words = withContext(Dispatchers.Default) { dao.getAllWordsOnce() }
        if (words.isEmpty()) {
            ExportMessage("Chưa có từ vựng nào để xuất.", NoticeTone.Warning)
        } else {
            val (fileName, content) = withContext(Dispatchers.Default) { VocabularyExporter.export(words, format) }
            PlatformShareHandler.shareTextFile(fileName, content, format.mimeType)
            ExportMessage("Đã xuất ${words.size} từ dạng ${format.label}", NoticeTone.Success)
        }
    } catch (e: Exception) {
        // Không đưa thông báo kỹ thuật thô (đường dẫn tệp, tên lớp Java...) cho người dùng
        ExportMessage("Không xuất được từ vựng. Vui lòng thử lại.", NoticeTone.Error)
    }
}

private fun iconOf(format: Format): ImageVector = when (format) {
    Format.CSV -> TruyenCloudIcons.List
    Format.TSV_ANKI -> TruyenCloudIcons.School
    Format.JSON -> TruyenCloudIcons.Layers
    Format.MARKDOWN -> TruyenCloudIcons.BookOpen
}

private fun describe(format: Format): String = when (format) {
    Format.CSV -> "Bảng tính, Excel, Google Sheets (ô bắt đầu bằng = + - @ có thêm dấu ' phía trước)"
    Format.TSV_ANKI -> "Import thẳng vào Anki flashcards"
    Format.JSON -> "Dữ liệu đầy đủ, lập trình"
    Format.MARKDOWN -> "Bảng đẹp, chia sẻ dễ đọc"
}
