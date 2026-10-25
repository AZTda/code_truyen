package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppPalette
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.reader.GlossaryEntry
import com.truyencloud.shared.reader.GlossaryOrigin

/**
 * Một dòng của bảng thuật ngữ: "nguyên văn → cách dịch" kèm nhãn nguồn gốc. Mục trên máy sửa / ghim / xoá được; mục của máy chủ
 * (nhãn "tự động" hoặc "chung") chỉ đọc — bấm Sửa sẽ tạo một mục trên máy ghi đè nó.
 */
@Composable
internal fun GlossaryEntryRow(
    entry: GlossaryEntry,
    isEditing: Boolean,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: (String) -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    FlatCard(
        modifier = modifier.fillMaxWidth(),
        outlined = true,
        contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.source,
                        style = AppText.bookTitle,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(text = "  →  ", style = AppText.secondary, color = colors.textTertiary)
                    if (!isEditing) {
                        Text(
                            text = entry.target,
                            style = AppText.body,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                if (entry.editable && !isEditing) {
                    QuietIconButton(
                        icon = if (entry.pinned) TruyenCloudIcons.BookmarkFilled else TruyenCloudIcons.BookmarkBorder,
                        contentDescription = if (entry.pinned) "Bỏ ghim" else "Ghim",
                        onClick = onTogglePin,
                        tint = if (entry.pinned) colors.accent else colors.textSecondary
                    )
                }
            }

            if (isEditing) {
                GlossaryTargetEditor(initial = entry.target, onSave = onSave, onCancel = onCancelEdit)
            } else {
                // Nhãn nguồn gốc bên trái, hành động Sửa / Xóa bên phải trên cùng một hàng (gọn hơn xếp hai hàng)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlossaryBadges(entry, colors, Modifier.weight(1f))
                    TextActionButton(text = "Sửa", onClick = onStartEdit)
                    if (entry.editable) TextActionButton(text = "Xóa", onClick = onDelete, destructive = true)
                }
            }
        }
    }
}

/** Nhãn nguồn gốc dưới dạng chữ 12sp trên nền tô nhạt (có chữ nên không chỉ dựa vào màu). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlossaryBadges(entry: GlossaryEntry, colors: AppPalette, modifier: Modifier = Modifier) {
    val labels = buildList<Triple<String, Color, Color>> {
        if (entry.pinned) add(Triple("Ghim", colors.accentSoft, colors.onAccentSoft))
        when (entry.origin) {
            GlossaryOrigin.SERVER_AUTO -> add(Triple("tự động", colors.cardHigh, colors.textSecondary))
            GlossaryOrigin.SERVER_SHARED -> add(Triple("chung", colors.cardHigh, colors.textSecondary))
            GlossaryOrigin.LOCAL -> {
                if (entry.global) add(Triple("mọi truyện", colors.accentSoft, colors.onAccentSoft))
                if (entry.overridesServer) add(Triple("ghi đè máy chủ", colors.warningSoft, colors.warning))
            }
        }
    }
    if (labels.isEmpty()) {
        Spacer(modifier = modifier)
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        labels.forEach { (text, fill, content) ->
            ReaderTag(text = text, fill = fill, content = content)
        }
    }
}

@Composable
private fun GlossaryTargetEditor(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    val colors = LocalCustomColors.current
    var text by remember(initial) { mutableStateOf(initial) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReaderInputField(
            value = text,
            onValueChange = { text = it },
            placeholder = "Cách dịch muốn dùng",
            modifier = Modifier.weight(1f),
            containerColor = colors.cardHigh,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text) })
        )
        PrimaryButton(text = "Lưu", onClick = { onSave(text) })
        TextActionButton(text = "Hủy", onClick = onCancel)
    }
}
