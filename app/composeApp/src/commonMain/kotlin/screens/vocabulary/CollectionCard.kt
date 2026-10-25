package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.components.InsetIconTile
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.data.SavedWord

/**
 * Thẻ một bộ sưu tập từ (theo truyện hoặc theo chủ đề): tiêu đề + số từ, hàng chip từ (chạm để nghe), rồi hai hành động
 * chữ ngăn bằng đường kẻ mảnh. [serifTitle] chỉ bật cho tên truyện. Khi chưa có từ thì hiện [emptyText] và không có hành động.
 */
@Composable
internal fun CollectionCard(
    title: String,
    subtitle: String,
    words: List<SavedWord>,
    onSpeak: (String) -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onWeave: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    badge: String? = null,
    serifTitle: Boolean = false,
    emptyText: String? = null
) {
    val c = LocalCustomColors.current
    FlatCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    InsetIconTile(icon, InsetIconTint.Accent)
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = if (serifTitle) AppText.bookTitle.copy(fontSize = 16.sp, lineHeight = 22.sp) else AppText.bodyStrong,
                        color = c.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(text = subtitle, style = AppText.secondary, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    StatusLabel(badge, tone = LabelTone.Accent)
                }
            }

            if (words.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(words, key = { it.id }) { word ->
                        FilterChip8(
                            label = word.word,
                            selected = false,
                            trailingText = cefrLabel(word.cefrLevel),
                            containerColor = c.cardHigh,
                            onClick = { onSpeak(word.word) }
                        )
                    }
                }
                HairlineDivider()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextActionButton(text = primaryLabel, onClick = onPrimary, modifier = Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(24.dp).background(c.hairline))
                    TextActionButton(text = "Dệt vi truyện", onClick = onWeave, modifier = Modifier.weight(1f))
                }
            } else if (emptyText != null) {
                Text(
                    text = emptyText,
                    style = AppText.secondary,
                    color = c.textTertiary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
                )
            }
        }
    }
}
