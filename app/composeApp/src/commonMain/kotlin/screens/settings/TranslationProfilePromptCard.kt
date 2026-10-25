package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.LocalCustomColors

/** Gợi ý nhanh: (nhãn chip, câu chèn vào chỉ dẫn). */
internal val QUICK_SUGGESTION_TAGS = listOf(
    "Hào sảng" to "Giọng văn hào sảng, phóng khoáng",
    "Huynh - Muội" to "Xưng hô Huynh - Muội chuẩn kiếm hiệp",
    "Giữ kiếm chiêu" to "Giữ nguyên tên chiêu thức và cảnh giới võ học",
    "Văn phong cổ" to "Văn phong cổ trang trang trọng, mượt mà"
)

/**
 * Chỉ dẫn dịch thuật tùy chỉnh: chip gợi ý chèn nhanh, ô nhập nhiều dòng, thông báo kết quả lưu và nút lưu.
 * Mọi điều khiển tắt khi [enabled] = false (không phải quản trị viên).
 */
@Composable
fun TranslationProfilePromptCard(
    customPrompt: String,
    onCustomPromptChange: (String) -> Unit,
    quickSuggestionTags: List<Pair<String, String>>,
    saveMessage: String,
    isSaving: Boolean,
    onSaveProfile: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = LocalCustomColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            quickSuggestionTags.forEach { (label, promptText) ->
                FilterChip8(
                    label = label,
                    selected = false,
                    leadingIcon = TruyenCloudIcons.Add,
                    enabled = enabled,
                    containerColor = colors.cardHigh,
                    onClick = {
                        val current = customPrompt.trim()
                        val next = when {
                            current.isEmpty() -> promptText
                            !current.contains(promptText) -> "$current, $promptText"
                            else -> current
                        }
                        onCustomPromptChange(next)
                    }
                )
            }
        }

        SettingsTextField(
            label = "",
            value = customPrompt,
            onValueChange = onCustomPromptChange,
            placeholder = "Gõ chỉ dẫn riêng cho AI (ví dụ: 'Giọng văn hào sảng, xưng huynh - muội, giữ nguyên kiếm chiêu...')",
            singleLine = false,
            minHeight = 120.dp,
            imeAction = ImeAction.Default,
            enabled = enabled,
            containerColor = colors.cardHigh
        )

        if (saveMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            NoticeBanner(
                text = saveMessage,
                tone = if (saveMessage.startsWith("Lỗi", ignoreCase = true)) NoticeTone.Error else NoticeTone.Success
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        PrimaryButton(
            text = if (isSaving) "Đang lưu cấu hình..." else "Lưu hồ sơ văn phong",
            onClick = onSaveProfile,
            enabled = enabled && !isSaving,
            isLoading = isSaving,
            leadingIcon = TruyenCloudIcons.Save,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
