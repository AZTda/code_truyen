package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.SecondaryButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

@Composable
fun RetranslateDialog(
    chapterNumber: Int,
    chapterTitle: String,
    prompt: String,
    initialTargetLanguage: String = "vi",
    initialEnglishLevel: String = "c1",
    onPromptChange: (String) -> Unit,
    onConfirm: (customPrompt: String?, recrawl: Boolean, targetLanguage: String, englishLevel: String) -> Unit,
    onRecrawlOnly: () -> Unit = {},
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        RetranslateDialogBody(
            chapterNumber, chapterTitle, prompt, initialTargetLanguage, initialEnglishLevel,
            onPromptChange, onConfirm, onRecrawlOnly, onDismiss
        )
    }
}

/** Thân hộp thoại dịch lại (panel + nội dung); tách riêng khỏi cửa sổ Dialog để xem trước mà không cần cửa sổ. */
@Composable
internal fun RetranslateDialogBody(
    chapterNumber: Int,
    chapterTitle: String,
    prompt: String,
    initialTargetLanguage: String,
    initialEnglishLevel: String,
    onPromptChange: (String) -> Unit,
    onConfirm: (customPrompt: String?, recrawl: Boolean, targetLanguage: String, englishLevel: String) -> Unit,
    onRecrawlOnly: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalCustomColors.current
    var localPrompt by remember(prompt) { mutableStateOf(prompt) }
    var recrawlRaw by remember { mutableStateOf(false) }
    var selectedLanguage by remember(initialTargetLanguage) { mutableStateOf(initialTargetLanguage) }
    var selectedEnglishLevel by remember(initialEnglishLevel) { mutableStateOf(initialEnglishLevel.lowercase()) }

    val presetSuggestions = remember(selectedLanguage, selectedEnglishLevel) {
        when (selectedLanguage) {
            "en" -> when (selectedEnglishLevel) {
                "b1" -> listOf(
                    "Oxford 3000: Từ vựng dễ hiểu, cấu trúc câu rõ ràng",
                    "Hội thoại đơn giản, tự nhiên, gần gũi",
                    "Tránh thành ngữ phức tạp, tập trung vào cốt truyện",
                    "Ngữ pháp trực tiếp, phù hợp làm quen đọc tiếng Anh"
                )
                "b2" -> listOf(
                    "Tiểu thuyết bestseller hiện đại (Contemporary Fiction)",
                    "Hội thoại sinh động, thành ngữ tự nhiên",
                    "Cấu trúc câu phong phú, nhịp độ lôi cuốn",
                    "Phù hợp giải trí và nâng cao phản xạ đọc"
                )
                "c1" -> listOf(
                    "Tiếng Anh văn học lãng mạn C1 (Literary Romance)",
                    "Tiếng Anh hiện đại, giàu tính tạo hình & cảm xúc",
                    "Oxford C1: Giàu từ vựng ẩn dụ, nhịp điệu cuốn hút",
                    "Tiếng Anh tự nhiên, lưu loát chuẩn New Yorker",
                    "Hội thoại tự nhiên, khắc họa sâu tâm lý nhân vật"
                )
                "c2" -> listOf(
                    "Oxford Literary Masterpiece: Tuyệt tác văn phong cổ điển",
                    "Từ vựng uyên bác, giàu thi vị & tính triết lý",
                    "Nhịp điệu thi ca, nhạc tính câu văn tao nhã",
                    "Khắc họa nội tâm và bối cảnh ở đẳng cấp văn hào"
                )
                else -> listOf(
                    "Tiếng Anh văn học lãng mạn C1 (Literary Romance)",
                    "Tiếng Anh hiện đại, giàu tính tạo hình & cảm xúc"
                )
            }
            "bilingual" -> listOf(
                "Đối ứng chuẩn xác từng câu Anh (${selectedEnglishLevel.uppercase()}) - Việt",
                "Bản dịch Anh trau chuốt kèm bản dịch Việt mượt mà",
                "Cấu trúc đoạn đối ứng 1-1 rõ ràng, dễ so sánh",
                "Thuận tiện vừa đọc truyện vừa học từ vựng"
            )
            else -> listOf(
                "Trau chuốt văn phong mượt mà, cảm xúc hơn",
                "Xưng hô cổ trang: Nàng - Chàng",
                "Xưng hô huynh đệ: Huynh - Muội",
                "Văn phong hiện đại: Anh - Em / Cô - Tôi",
                "Văn phong hào sảng, kiếm hiệp cổ điển",
                "Sát nghĩa gốc, chính xác từng chi tiết"
            )
        }
    }

    // Hộp thoại kiểu iOS: tiêu đề cố định, thân cuộn được, hai nút ghim đáy (xem ReaderDialogFrame)
    ReaderDialogFrame(
        onDismiss = onDismiss,
        maxWidth = 480.dp,
        header = {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Dịch lại bằng AI",
                    style = AppText.sectionHeader,
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { heading() }
                )
                val cleanedTitle = remember(chapterTitle, chapterNumber) {
                    val t = chapterTitle.trim()
                    val regex = Regex("^(?:Chương|Chapter)\\s*\\d+[:\\s-]*", RegexOption.IGNORE_CASE)
                    val stripped = t.replace(regex, "").trim()
                    if (stripped.isNotBlank()) stripped else t.ifBlank { "Đang đọc" }
                }
                Text(
                    text = "Chương $chapterNumber: $cleanedTitle",
                    style = AppText.secondary,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        actions = {
            SecondaryButton(text = "Hủy", onClick = onDismiss, modifier = Modifier.weight(1f))
            PrimaryButton(
                text = "Dịch lại ngay",
                onClick = {
                    onConfirm(localPrompt.ifBlank { null }, recrawlRaw, selectedLanguage, selectedEnglishLevel)
                },
                modifier = Modifier.weight(1.5f)
            )
        }
    ) {
        Spacer(modifier = Modifier.height(Spacing.sm))

        // Ngôn ngữ đích + trình độ
        RetranslateOptionsSection(
            selectedLanguage = selectedLanguage,
            onSelectLanguage = { selectedLanguage = it },
            selectedEnglishLevel = selectedEnglishLevel,
            onSelectEnglishLevel = { selectedEnglishLevel = it }
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        // Gợi ý phong cách, lời nhắc và tuỳ chọn thu thập lại bản gốc
        RetranslatePromptSection(
            localPrompt = localPrompt,
            onPromptChange = {
                localPrompt = it
                onPromptChange(it)
            },
            presetSuggestions = presetSuggestions,
            recrawlRaw = recrawlRaw,
            onToggleRecrawl = { recrawlRaw = it },
            onRecrawlOnly = onRecrawlOnly
        )
    }
}
