package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.ButtonShape
import com.truyencloud.compose.theme.LocalCustomColors

/** Hàng câu hỏi gợi ý (bấm để hỏi ngay). Khoá lại khi AI đang trả lời một câu khác. */
@Composable
internal fun CompanionSuggestions(suggestions: List<String>, enabled: Boolean, onSend: (String) -> Unit) {
    if (suggestions.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ReaderSheetInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        suggestions.forEach { prompt ->
            FilterChip8(
                label = prompt,
                selected = false,
                onClick = { onSend(prompt) },
                modifier = Modifier.widthIn(max = 280.dp),
                enabled = enabled
            )
        }
    }
}

/** Ô nhập câu hỏi + nút gửi. [enabled] = false (AI đang trả lời) thì chưa gửi được câu mới, nhưng vẫn gõ sẵn được. */
@Composable
internal fun CompanionInputRow(enabled: Boolean, onSend: (String) -> Unit) {
    val colors = LocalCustomColors.current
    var inputText by remember { mutableStateOf("") }
    val canSend = enabled && inputText.isNotBlank()
    val submit = {
        if (canSend) {
            val q = inputText
            inputText = ""
            onSend(q)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ReaderSheetInset, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReaderInputField(
            value = inputText,
            onValueChange = { inputText = it },
            placeholder = "Hỏi AI về chương này…",
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() })
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(ButtonShape)
                .background(if (canSend) colors.accent else colors.cardHigh)
                .clickable(enabled = canSend, role = Role.Button, onClick = { submit() }),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = TruyenCloudIcons.Send,
                contentDescription = "Gửi",
                tint = if (canSend) colors.onAccent else colors.textTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
