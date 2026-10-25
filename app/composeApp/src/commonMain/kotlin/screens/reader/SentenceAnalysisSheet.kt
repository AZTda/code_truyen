package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.SentenceTranslateResponse

/**
 * Bảng phân tích ngữ pháp một câu tiếng Anh.
 *
 * Endpoint `/v1/english/translate-sentence` trả về `grammar_points` + `key_vocabulary` đã có sẵn trong backend và cả trong
 * `EnglishApi`: đây là thứ người học trình độ C1 cần khi gặp một câu văn học phức tạp.
 */
@Composable
fun SentenceAnalysisSheet(
    sentence: String,
    data: SentenceTranslateResponse?,
    isLoading: Boolean,
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    onSpeak: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    ReaderModalSheet(onDismiss = onDismiss, title = "Phân tích câu và ngữ pháp") {
        SentenceAnalysisBody(sentence, data, isLoading, errorMessage, onRetry, onSpeak)
    }
}

/** Phần thân cuộn được của bảng phân tích câu: câu gốc + nút đọc, rồi đang tải / lỗi / kết quả. */
@Composable
internal fun ColumnScope.SentenceAnalysisBody(
    sentence: String,
    data: SentenceTranslateResponse?,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onSpeak: (String) -> Unit
) {
    val colors = LocalCustomColors.current
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ReaderSheetInset)
    ) {
        // Câu gốc (chữ của truyện nên dùng serif) + nút đọc câu
        FlatCard(modifier = Modifier.fillMaxWidth(), outlined = true) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = sentence,
                    style = AppText.body.copy(fontFamily = ReaderFont),
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f).padding(top = 4.dp)
                )
                QuietIconButton(TruyenCloudIcons.VolumeUp, "Đọc câu", { onSpeak(sentence) }, tint = colors.accent)
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        when {
            isLoading && data == null -> AiLoadingPanel("Đang phân tích cấu trúc câu…", height = 160.dp)

            data == null -> Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage ?: "Chưa phân tích được câu này.",
                    style = AppText.secondary,
                    color = colors.error,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                PrimaryButton(text = "Thử lại", onClick = onRetry, leadingIcon = TruyenCloudIcons.Refresh)
            }

            else -> SentenceAnalysisResult(data)
        }
        Spacer(modifier = Modifier.height(Spacing.lg))
    }
}

@Composable
private fun SentenceAnalysisResult(data: SentenceTranslateResponse) {
    val colors = LocalCustomColors.current

    if (data.vietnameseTranslation.isNotBlank()) {
        ReaderSectionLabel("Bản dịch tiếng Việt")
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = data.vietnameseTranslation, style = AppText.body, color = colors.textPrimary)
        Spacer(modifier = Modifier.height(Spacing.lg))
    }

    if (data.grammarPoints.isNotEmpty()) {
        ReaderSectionLabel("Cấu trúc ngữ pháp")
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            data.grammarPoints.forEach { gp ->
                FlatCard(modifier = Modifier.fillMaxWidth(), outlined = true) {
                    Column {
                        Text(text = gp.structure, style = AppText.bodyStrong, color = colors.onAccentSoft)
                        if (gp.explanation.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = gp.explanation, style = AppText.secondary, color = colors.textPrimary)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.lg))
    }

    if (data.keyVocabulary.isNotEmpty()) {
        ReaderSectionLabel("Từ vựng cốt lõi")
        Spacer(modifier = Modifier.height(8.dp))
        data.keyVocabulary.forEach { kv ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Text(
                    text = kv.word,
                    style = AppText.bookTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.width(112.dp)
                )
                Text(text = kv.meaning, style = AppText.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
            }
        }
    }

    if (data.grammarPoints.isEmpty() && data.keyVocabulary.isEmpty() && data.vietnameseTranslation.isBlank()) {
        Text(
            text = "Không có điểm ngữ pháp đáng chú ý trong câu này.",
            style = AppText.secondary.copy(fontStyle = FontStyle.Italic),
            color = colors.textSecondary
        )
    }
}
