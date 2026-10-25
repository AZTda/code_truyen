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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.WordExplainResponse

@Composable
fun EnglishWordSheet(
    word: String,
    data: WordExplainResponse?,
    isLoading: Boolean,
    isSaved: Boolean,
    /** Dạng khác của từ đã có thẻ (vd. "run" khi xem "running") — hiện trên nút để người đọc biết mình bỏ lưu thẻ nào. */
    savedForm: String? = null,
    /** Câu nguyên văn trong truyện nơi người đọc chạm vào từ. */
    contextSentence: String = "",
    /** Thông báo lỗi thật. Khi khác null, KHÔNG được hiển thị dữ liệu từ điển giả. */
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    onSaveWord: () -> Unit,
    onSpeakWord: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ReaderModalSheet(
        onDismiss = onDismiss,
        title = "Từ điển",
        headerActions = { EnglishWordSaveToggle(isSaved = isSaved, savedForm = savedForm, onToggle = onSaveWord) }
    ) {
        EnglishWordSheetBody(word, data, isLoading, isSaved, contextSentence, errorMessage, onRetry, onSaveWord, onSpeakWord)
    }
}

/** Phần thân cuộn được của bảng tra từ (đặt dưới hàng tiêu đề): đang tải, lỗi thật, hoặc mục từ điển. */
@Composable
internal fun ColumnScope.EnglishWordSheetBody(
    word: String,
    data: WordExplainResponse?,
    isLoading: Boolean,
    isSaved: Boolean,
    contextSentence: String,
    errorMessage: String?,
    onRetry: () -> Unit,
    onSaveWord: () -> Unit,
    onSpeakWord: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ReaderSheetInset)
    ) {
        when {
            isLoading && data == null -> AiLoadingPanel("Đang khảo cứu từ nguyên và ngữ cảnh…", height = 200.dp)
            data == null -> EnglishWordErrorView(
                word = word,
                errorMessage = errorMessage,
                contextSentence = contextSentence,
                onRetry = onRetry,
                onSpeakWord = onSpeakWord
            )
            else -> EnglishWordContent(
                data = data,
                isSaved = isSaved,
                contextSentence = contextSentence,
                onSaveWord = onSaveWord,
                onSpeakWord = onSpeakWord
            )
        }
        Spacer(modifier = Modifier.height(Spacing.lg))
    }
}

/** Mục từ điển đã tra được: từ + phiên âm + loa, loại từ và trình độ, nghĩa trong ngữ cảnh, phần phân tích và nút lưu. */
@Composable
private fun EnglishWordContent(
    data: WordExplainResponse,
    isSaved: Boolean,
    contextSentence: String,
    onSaveWord: () -> Unit,
    onSpeakWord: (String) -> Unit
) {
    val colors = LocalCustomColors.current

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = data.word,
                style = AppText.wordTitle.copy(fontSize = 26.sp, lineHeight = 32.sp),
                color = colors.textPrimary
            )
            if (data.ipaUs.isNotBlank() || data.ipaUk.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (data.ipaUs.isNotBlank()) {
                        Text(text = "US ${data.ipaUs}", style = AppText.secondary, color = colors.textSecondary)
                    }
                    if (data.ipaUs.isNotBlank() && data.ipaUk.isNotBlank()) Spacer(modifier = Modifier.width(12.dp))
                    if (data.ipaUk.isNotBlank()) {
                        Text(text = "UK ${data.ipaUk}", style = AppText.secondary, color = colors.textSecondary)
                    }
                }
            }
        }
        QuietIconButton(
            icon = TruyenCloudIcons.VolumeUp,
            contentDescription = "Phát âm",
            onClick = { onSpeakWord(data.word) },
            tint = colors.accent
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (data.partOfSpeech.isNotBlank()) {
            ReaderTag(text = data.partOfSpeech, fill = colors.card, content = colors.textSecondary)
        }
        if (data.cefrLevel.isNotBlank()) {
            ReaderTag(text = "CEFR ${data.cefrLevel.uppercase()}", fill = colors.accentSoft, content = colors.onAccentSoft)
        }
    }

    Spacer(modifier = Modifier.height(Spacing.lg))
    EnglishWordDefinitionCard(contextMeaningVi = data.contextMeaningVi, contextSentence = contextSentence)

    Spacer(modifier = Modifier.height(Spacing.md))
    EnglishWordAnalysisSection(data = data)

    // Lưu / bỏ lưu vào sổ từ vựng
    if (isSaved) {
        TonalButton(
            text = "Đã lưu · chạm để bỏ lưu",
            onClick = onSaveWord,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = TruyenCloudIcons.CheckCircle
        )
    } else {
        PrimaryButton(
            text = "Lưu vào sổ từ vựng",
            onClick = onSaveWord,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = TruyenCloudIcons.Bookmark
        )
    }
}
