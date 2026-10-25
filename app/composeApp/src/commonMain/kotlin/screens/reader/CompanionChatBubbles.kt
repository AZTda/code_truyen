package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.CompanionChatMessage
import com.truyencloud.shared.reader.CompanionContext

private val BubbleRadius = 16.dp
private val BubbleTail = 4.dp

/** Bong bóng một tin nhắn: của người đọc (bên phải, nền tô nhạt màu nhấn) hoặc của AI (bên trái, nền thẻ). Chữ luôn dùng màu chữ chính. */
@Composable
internal fun CompanionMessageBubble(msg: CompanionChatMessage) {
    val colors = LocalCustomColors.current
    val isUser = msg.role == CompanionContext.ROLE_USER
    val shape = RoundedCornerShape(
        topStart = BubbleRadius,
        topEnd = BubbleRadius,
        bottomStart = if (isUser) BubbleRadius else BubbleTail,
        bottomEnd = if (isUser) BubbleTail else BubbleRadius
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(colors.card)
                .then(if (isUser) Modifier.background(colors.accentSoft) else Modifier.border(1.dp, colors.hairline, shape))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text = msg.content, style = AppText.body, color = colors.textPrimary)
        }
    }
}

@Composable
internal fun CompanionTypingIndicator() {
    val colors = LocalCustomColors.current
    Row(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(BubbleRadius))
            .background(colors.card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accent)
        Text(text = "AI đang suy ngẫm…", style = AppText.secondary, color = colors.textSecondary)
    }
}

/**
 * Lượt hỏi thất bại (máy chủ gắn cờ dự phòng, hết phiên, mất mạng...). Hiện như một lỗi — không bao giờ như câu trả lời của AI —
 * kèm nút "Thử lại" hỏi lại đúng câu vừa rồi.
 */
@Composable
internal fun CompanionErrorBubble(message: String, onRetry: (() -> Unit)?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Start) {
        ReaderBannerCard(
            message = message,
            title = "AI chưa trả lời được",
            tone = NoticeTone.Error,
            actions = listOfNotNull(onRetry?.let { BannerAction("Thử lại", primary = true, onClick = it) }),
            modifier = Modifier.widthIn(max = 320.dp)
        )
    }
}
