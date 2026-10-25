package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.shared.api.CompanionChatMessage

/**
 * Trò chuyện cùng AI. Lượt thất bại ([errorMessage] khác null) hiện thành bong bóng lỗi ở cuối cuộc trò chuyện kèm nút
 * [onRetry]; câu trả lời dự phòng của máy chủ không bao giờ nằm trong [chatHistory].
 */
@Composable
fun CompanionChatSheet(
    novelTitle: String,
    chapterTitle: String,
    selectedCharacter: String,
    chatHistory: List<CompanionChatMessage>,
    suggestedQuestions: List<String>,
    isTyping: Boolean,
    /** Câu trả lời đang được stream (còn đúng cả khi chữ đã hiện, lúc [isTyping] đã tắt): khoá ô nhập tới khi luồng kết thúc. */
    isStreaming: Boolean = false,
    errorMessage: String?,
    onSendMessage: (String) -> Unit,
    onSelectCharacter: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    canRetry: Boolean = false,
    onRetry: () -> Unit = {}
) {
    val listState = rememberLazyListState()

    // Theo sát tin mới nhất (kể cả khi chữ đang được stream dài ra) và bong bóng lỗi / chỉ báo "đang suy ngẫm"
    val lastLength = chatHistory.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(chatHistory.size, lastLength, isTyping, errorMessage) {
        val extra = (if (isTyping) 1 else 0) + (if (errorMessage != null) 1 else 0)
        val lastIndex = chatHistory.size + extra - 1
        if (lastIndex >= 0) listState.scrollToItem(lastIndex)
    }

    ReaderSheet(
        onDismiss = onDismiss,
        title = "Trợ lý AI",
        subtitle = "$novelTitle · $chapterTitle",
        modifier = modifier.fillMaxHeight(0.78f),
        withIme = true,
        headerActions = {
            if (chatHistory.isNotEmpty()) {
                QuietIconButton(TruyenCloudIcons.Delete, "Xóa hội thoại", onClearHistory)
            }
        }
    ) {
        CompanionPersonaRow(selected = selectedCharacter, onSelect = onSelectCharacter)
        HairlineDivider(modifier = Modifier.padding(top = 4.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = ReaderSheetInset, vertical = 8.dp)
        ) {
            if (chatHistory.isEmpty() && errorMessage == null) {
                item {
                    EmptyState(
                        message = "Hỏi AI về thế giới quan, nhân vật, động cơ ngầm hoặc dự đoán tình tiết",
                        icon = TruyenCloudIcons.Chat
                    )
                }
            }

            itemsIndexed(chatHistory) { _, msg -> CompanionMessageBubble(msg) }

            if (isTyping) item { CompanionTypingIndicator() }
            if (errorMessage != null) {
                item { CompanionErrorBubble(message = errorMessage, onRetry = if (canRetry) onRetry else null) }
            }
        }

        // Gửi câu mới giữa lúc câu trước đang stream sẽ cắt ngang nó và để lại nửa câu trả lời: khoá tới khi luồng thật sự xong
        val locked = isTyping || isStreaming
        CompanionSuggestions(suggestions = suggestedQuestions, enabled = !locked, onSend = onSendMessage)
        CompanionInputRow(enabled = !locked, onSend = onSendMessage)
    }
}
