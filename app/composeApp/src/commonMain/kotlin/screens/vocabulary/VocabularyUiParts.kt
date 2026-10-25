package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppPalette
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.SavedWord

/** Ngữ nghĩa của nhãn nhỏ: quyết định cặp màu nền nhạt / chữ (không bao giờ chỉ dựa vào màu, nhãn luôn có chữ). */
internal enum class LabelTone { Neutral, Accent, Success, Warning, Error }

/** Nhãn trạng thái nhỏ 12sp bo 6, nền tô nhạt theo [tone]. */
@Composable
internal fun StatusLabel(text: String, modifier: Modifier = Modifier, tone: LabelTone = LabelTone.Neutral) {
    val (fill, content) = labelColors(tone, LocalCustomColors.current)
    Text(
        text = text,
        style = AppText.caption,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

// Chữ màu ngữ nghĩa trên nền nhạt cùng màu chỉ ~4,0-4,3:1 ở giao diện sáng (dưới 4,5): nhãn ngữ nghĩa giữ nền tô nhạt
// để nhận ra tông, còn chữ dùng màu chính (đạt >= 12:1). Chỉ nhãn nhấn dùng onAccentSoft vì cặp này đã được đo đạt.
private fun labelColors(tone: LabelTone, c: AppPalette): Pair<Color, Color> = when (tone) {
    LabelTone.Neutral -> c.cardHigh to c.textSecondary
    LabelTone.Accent -> c.accentSoft to c.onAccentSoft
    LabelTone.Success -> c.successSoft to c.textPrimary
    LabelTone.Warning -> c.warningSoft to c.textPrimary
    LabelTone.Error -> c.errorSoft to c.textPrimary
}

/** Tiêu đề nhỏ của một khối nội dung: 13sp Medium chữ phụ, viết hoa chữ đầu câu (không in hoa, không giãn chữ). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AppText.secondaryStrong,
        color = LocalCustomColors.current.textSecondary,
        modifier = modifier.semantics { heading() }
    )
}

/**
 * Hàng chip cuộn ngang chạy sát mép màn hình (đệm ngang nằm trong vùng cuộn), tự cuộn tới chip đang chọn để lựa chọn hiện
 * tại không bị khuất bên phải. [count] là tổng số chip, [selectedIndex] là vị trí chip đang chọn.
 */
@Composable
internal fun ChipScrollRow(
    count: Int,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    val state = rememberLazyListState()
    LaunchedEffect(selectedIndex, count) {
        if (selectedIndex in 0 until count) state.animateScrollToItem(selectedIndex)
    }
    LazyRow(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.pageH),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        content = content
    )
}

/** "B2", "C1" viết hoa cả hai ký tự; mức dài như "Academic" chỉ viết hoa chữ đầu. */
internal fun cefrLabel(level: String): String {
    val t = level.trim()
    return if (t.length <= 2) t.uppercase() else t.lowercase().replaceFirstChar { it.uppercase() }
}

/** "US /ɪɡˈzæmpl/ · UK /ɪɡˈzɑːmpl/"; rỗng khi thẻ chưa có phiên âm. */
internal fun ipaLine(word: SavedWord): String = listOfNotNull(
    if (word.ipaUs.isNotBlank()) "US ${word.ipaUs}" else null,
    if (word.ipaUk.isNotBlank()) "UK ${word.ipaUk}" else null
).joinToString(" · ")
