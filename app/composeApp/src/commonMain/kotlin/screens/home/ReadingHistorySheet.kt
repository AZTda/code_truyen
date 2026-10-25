package com.truyencloud.compose.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.library.ReadingHistoryItem
import com.truyencloud.shared.library.ReadingHistoryStore

/**
 * Tờ trượt Nhật ký Lịch sử Đọc chi tiết (Timeline các chương vừa đọc / nghe kèm mốc thời gian).
 * Mọi biểu tượng đều dùng icon vector [TruyenCloudIcons].
 */
@Composable
fun ReadingHistorySheet(
    recentNovels: List<Novel>,
    onResumeHistoryItem: (ReadingHistoryItem) -> Unit,
    onDismiss: () -> Unit
) {
    var clearCount by remember { mutableStateOf(0) }
    val items = remember(recentNovels, clearCount) {
        ReadingHistoryStore.shared.mergedWith(recentNovels)
    }
    val nowMs = remember(items) { getCurrentEpochMillis() }

    IosSheet(onDismissRequest = onDismiss, title = "Nhật ký đọc gần đây") {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (items.isEmpty()) {
                EmptyState(
                    message = "Chưa có nhật ký đọc",
                    subMessage = "Các chương bạn vừa đọc hoặc nghe Audiobook sẽ xuất hiện tại đây theo trình tự thời gian.",
                    icon = TruyenCloudIcons.HistoryTimeline
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.pageH, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { "${it.novelId}_${it.chapterNumber}_${it.timestampMs}" }) { item ->
                        ReadingHistoryRow(
                            item = item,
                            nowMs = nowMs,
                            onClick = {
                                onDismiss()
                                onResumeHistoryItem(item)
                            }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.pageH, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextActionButton(
                        text = "Xóa nhật ký chi tiết",
                        onClick = {
                            ReadingHistoryStore.shared.clear()
                            clearCount++
                        },
                        destructive = true
                    )
                    TextActionButton(text = "Đóng", onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
fun ReadingHistoryRow(
    item: ReadingHistoryItem,
    nowMs: Long = getCurrentEpochMillis(),
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val timeLabel = ReadingHistoryStore.relativeTimeLabel(item.timestampMs, nowMs)
    val modeLabel = if (item.isAudio) "Nghe Audio" else "Đọc chương"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (item.isAudio) TruyenCloudIcons.Headphones else TruyenCloudIcons.BookOpen,
                contentDescription = modeLabel,
                tint = colors.accent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.novelTitle,
                style = AppText.secondaryStrong,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Ch.${item.chapterNumber} · ${item.chapterTitle}",
                style = AppText.captionRegular,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = timeLabel,
                style = AppText.caption,
                color = colors.accent,
                maxLines = 1
            )
            Icon(
                imageVector = TruyenCloudIcons.ChevronRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
