package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.repo.StudyConfig
import kotlin.math.roundToInt

private val NEW_CARDS_OPTIONS = listOf(5, 10, 12, 20, 30, 50)
private val MAX_REVIEWS_OPTIONS = listOf(40, 80, 120, 200)
private val RETENTION_OPTIONS = listOf(
    0.85 to "85% (Thư thả)",
    0.90 to "90% (Chuẩn)",
    0.95 to "95% (Nhớ kỹ)"
)

/**
 * Tờ chỉnh hạn mức học từ vựng hằng ngày (từ mới/ngày, lượt ôn tối đa/ngày, mục tiêu ghi nhớ FSRS).
 */
@Composable
internal fun StudyGoalDialog(
    initial: StudyConfig,
    onSave: (StudyConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalCustomColors.current
    var newCards by remember(initial) { mutableStateOf(initial.newCardsPerDay) }
    var maxReviews by remember(initial) { mutableStateOf(initial.maxReviewsPerDay) }
    var retention by remember(initial) {
        mutableStateOf(
            when {
                initial.desiredRetention <= 0.87 -> 0.85
                initial.desiredRetention >= 0.93 -> 0.95
                else -> 0.90
            }
        )
    }

    IosSheet(onDismissRequest = onDismiss, title = "Mục tiêu học mỗi ngày") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageH),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = "Điều chỉnh số lượng thẻ mới giới thiệu mỗi ngày và độ bền trí nhớ mong muốn theo thuật toán FSRS.",
                style = AppText.secondary,
                color = c.textSecondary
            )

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SectionLabel("Số từ mới mỗi ngày: $newCards từ")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    NEW_CARDS_OPTIONS.forEach { count ->
                        FilterChip8(
                            label = "$count",
                            selected = newCards == count,
                            onClick = { newCards = count }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SectionLabel("Tối đa lượt ôn mỗi ngày: $maxReviews thẻ")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    MAX_REVIEWS_OPTIONS.forEach { count ->
                        FilterChip8(
                            label = "$count",
                            selected = maxReviews == count,
                            onClick = { maxReviews = count }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SectionLabel("Mức độ nhớ mục tiêu FSRS: ${(retention * 100).roundToInt()}%")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    RETENTION_OPTIONS.forEach { (value, label) ->
                        FilterChip8(
                            label = label,
                            selected = retention == value,
                            onClick = { retention = value }
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                TonalButton(
                    text = "Hủy",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = "Lưu mục tiêu",
                    onClick = {
                        onSave(
                            StudyConfig(
                                newCardsPerDay = newCards,
                                maxReviewsPerDay = maxReviews,
                                desiredRetention = retention
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}

/**
 * Tờ thêm từ vựng mới thủ công vào Sổ từ (AI tự động tra phiên âm, nghĩa, loại từ, CEFR và câu ví dụ nếu bỏ trống).
 */
@Composable
internal fun AddCustomWordDialog(
    isLoading: Boolean,
    errorMessage: String?,
    onSubmit: (word: String, sentence: String, meaningVi: String) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalCustomColors.current
    var wordInput by remember { mutableStateOf("") }
    var meaningInput by remember { mutableStateOf("") }
    var sentenceInput by remember { mutableStateOf("") }

    IosSheet(onDismissRequest = onDismiss, title = "Thêm từ vựng mới") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageH),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = "Nhập từ hoặc cụm từ tiếng Anh. Nếu để trống nghĩa tiếng Việt, AI sẽ tự động tra cứu phiên âm, định nghĩa và ví dụ.",
                style = AppText.secondary,
                color = c.textSecondary
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Từ tiếng Anh (bắt buộc)")
                VocabularyTextInput(
                    value = wordInput,
                    onValueChange = { wordInput = it },
                    placeholder = "Ví dụ: serendipity, resilient...",
                    enabled = !isLoading,
                    singleLine = true
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Nghĩa tiếng Việt (tùy chọn — AI tự điền nếu để trống)")
                VocabularyTextInput(
                    value = meaningInput,
                    onValueChange = { meaningInput = it },
                    placeholder = "Ví dụ: sự tình cờ may mắn...",
                    enabled = !isLoading,
                    singleLine = true
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Câu ngữ cảnh / ví dụ tiếng Anh (tùy chọn)")
                VocabularyTextInput(
                    value = sentenceInput,
                    onValueChange = { sentenceInput = it },
                    placeholder = "Ví dụ: Finding that rare book was pure serendipity.",
                    enabled = !isLoading,
                    singleLine = false,
                    minHeight = 76.dp
                )
            }

            errorMessage?.let {
                NoticeBanner(text = it, tone = NoticeTone.Error)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                TonalButton(
                    text = "Đóng",
                    onClick = onDismiss,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = if (isLoading) "Đang tra & lưu..." else "Lưu vào Sổ từ",
                    onClick = { onSubmit(wordInput.trim(), sentenceInput.trim(), meaningInput.trim()) },
                    enabled = wordInput.isNotBlank() && !isLoading,
                    isLoading = isLoading,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}
