package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors

internal enum class AnswerState { Idle, Correct, Wrong }

/**
 * Ô đáp án trắc nghiệm (luyện tập, quiz): viền mảnh `outline` khi chưa chọn; sau khi trả lời, đáp án đúng viền + nền xanh
 * nhạt kèm dấu tích, lựa chọn sai viền + nền đỏ nhạt kèm dấu X (luôn có biểu tượng, không chỉ dựa vào màu).
 */
@Composable
internal fun AnswerTile(
    text: String,
    state: AnswerState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalCustomColors.current
    val (fill, border) = when (state) {
        AnswerState.Idle -> Color.Transparent to c.outline
        AnswerState.Correct -> c.successSoft to c.success
        AnswerState.Wrong -> c.errorSoft to c.error
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(InputShape)
            .background(fill)
            .border(1.dp, border, InputShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, style = AppText.body, color = c.textPrimary, modifier = Modifier.weight(1f))
        when (state) {
            AnswerState.Correct -> {
                Spacer(Modifier.width(12.dp))
                Icon(TruyenCloudIcons.CheckCircle, contentDescription = "Đáp án đúng", tint = c.success, modifier = Modifier.size(20.dp))
            }
            AnswerState.Wrong -> {
                Spacer(Modifier.width(12.dp))
                Icon(TruyenCloudIcons.Close, contentDescription = "Lựa chọn sai", tint = c.error, modifier = Modifier.size(20.dp))
            }
            AnswerState.Idle -> Unit
        }
    }
}
