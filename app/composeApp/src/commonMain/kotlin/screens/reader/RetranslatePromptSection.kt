package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Gợi ý phong cách (chọn nhiều, mỗi gợi ý là một dòng bấm được để đọc đủ chữ trên màn hẹp), ô lời nhắc cho AI và tuỳ chọn thu thập lại
 * bản gốc. Chuỗi gợi ý được GHÉP VÀO lời nhắc gửi AI nên giữ nguyên nội dung.
 */
@Composable
fun RetranslatePromptSection(
    localPrompt: String,
    onPromptChange: (String) -> Unit,
    presetSuggestions: List<String>,
    recrawlRaw: Boolean,
    onToggleRecrawl: (Boolean) -> Unit,
    onRecrawlOnly: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        ReaderSectionLabel("Gợi ý phong cách")
        Spacer(modifier = Modifier.height(Spacing.xs))

        Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            presetSuggestions.forEach { suggestion ->
                val isSelected = localPrompt.contains(suggestion)
                SuggestionRow(
                    text = suggestion,
                    selected = isSelected,
                    onToggle = {
                        val newPrompt = if (isSelected) {
                            localPrompt.replace(suggestion, "").trim()
                        } else {
                            if (localPrompt.isBlank()) suggestion else "$localPrompt, $suggestion"
                        }
                        onPromptChange(newPrompt)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))
        ReaderSectionLabel("Lời nhắc cho AI (tùy chọn)")
        Spacer(modifier = Modifier.height(Spacing.xs))
        ReaderInputField(
            value = localPrompt,
            onValueChange = onPromptChange,
            placeholder = "Ví dụ: đổi xưng hô nhân vật chính thành chàng - thiếp, dịch hài hước hơn…",
            singleLine = false,
            minHeight = 96.dp,
            maxLines = 4
        )

        Spacer(modifier = Modifier.height(Spacing.sm))
        RecrawlToggleRow(checked = recrawlRaw, onCheckedChange = onToggleRecrawl)

        if (recrawlRaw) {
            TextActionButton(
                text = "Chỉ tải lại bản gốc (không dịch)",
                onClick = onRecrawlOnly,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

/** Một gợi ý phong cách: dòng cao >= 48dp, dấu chọn bên trái (vòng rỗng / dấu tích) nên trạng thái không chỉ dựa vào màu. */
@Composable
private fun SuggestionRow(text: String, selected: Boolean, onToggle: () -> Unit) {
    val colors = LocalCustomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(InputShape)
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(TruyenCloudIcons.CheckCircle, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
        } else {
            Box(modifier = Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, colors.outline, CircleShape))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, style = AppText.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** Công tắc "Thu thập lại bản gốc từ nguồn": bấm cả hàng để bật / tắt. */
@Composable
private fun RecrawlToggleRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalCustomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Thu thập lại bản gốc từ nguồn", style = AppText.body, color = colors.textPrimary)
            Text(
                text = "Tải lại toàn văn nếu chương gốc bị cắt bớt hoặc thiếu dòng.",
                style = AppText.secondary,
                color = colors.textSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                checkedBorderColor = colors.accent,
                uncheckedThumbColor = colors.textSecondary,
                uncheckedTrackColor = colors.cardHigh,
                uncheckedBorderColor = colors.outline
            )
        )
    }
}
