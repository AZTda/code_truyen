package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.LoreExplainResponse

/**
 * Chốt cách dịch vào bảng thuật ngữ của truyện. [onSave] nhận `pinned`: "Ghi nhớ" lưu thường, "Ghim" lưu kèm cờ ghim
 * (luôn được gửi kèm khi dịch dù bảng đã dài).
 */
@Composable
fun LoreGlossarySection(
    loreData: LoreExplainResponse,
    targetInput: String,
    onTargetChange: (String) -> Unit,
    isSaved: Boolean,
    isSaving: Boolean,
    onSave: (Boolean) -> Unit,
    saveMessage: String?,
    glossarySize: Int,
    modifier: Modifier = Modifier,
    term: String = "",
    onReadOriginal: (() -> Unit)? = null,
    onOpenGlossary: (() -> Unit)? = null
) {
    val colors = LocalCustomColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        ReaderSectionLabel("Chốt cách dịch cho truyện này")
        Spacer(modifier = Modifier.height(8.dp))
        LoreSourceNote(term = term.ifBlank { loreData.term }, hanzi = loreData.hanzi, onReadOriginal = onReadOriginal)
        Spacer(modifier = Modifier.height(8.dp))

        ReaderInputField(
            value = targetInput,
            onValueChange = onTargetChange,
            placeholder = loreData.sino_vietnamese.ifBlank { "Cách dịch muốn dùng…" }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isSaved) {
                TonalButton(
                    text = "Đã nhớ",
                    onClick = { if (!isSaving) onSave(false) },
                    modifier = Modifier.weight(1f),
                    isLoading = isSaving,
                    leadingIcon = TruyenCloudIcons.CheckCircle
                )
            } else {
                PrimaryButton(
                    text = "Ghi nhớ",
                    onClick = { if (!isSaving) onSave(false) },
                    modifier = Modifier.weight(1f),
                    isLoading = isSaving,
                    leadingIcon = TruyenCloudIcons.Bookmark
                )
            }
            // "Ghim": lưu kèm cờ ghim — tên nhân vật chính nên ghim để luôn được gửi kèm khi dịch
            TonalButton(
                text = "Ghim",
                onClick = { onSave(true) },
                modifier = Modifier.weight(1f),
                enabled = !isSaving
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = saveMessage
                ?: if (glossarySize > 0) {
                    "$glossarySize thuật ngữ đang được dùng khi dịch truyện này."
                } else {
                    "Ghi nhớ để tên này không đổi cách viết giữa các chương."
                },
            style = AppText.captionRegular,
            color = if (saveMessage != null) colors.onAccentSoft else colors.textSecondary
        )
        if (onOpenGlossary != null) {
            TextActionButton(
                text = "Xem và sửa bảng thuật ngữ",
                onClick = onOpenGlossary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
