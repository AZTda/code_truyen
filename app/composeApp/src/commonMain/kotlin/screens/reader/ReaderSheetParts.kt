package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors

/** Nhãn mục trong sheet: 13sp Medium chữ phụ, viết hoa chữ đầu câu (thay cho nhãn IN HOA giãn chữ). */
@Composable
internal fun ReaderSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AppText.secondaryStrong,
        color = LocalCustomColors.current.textSecondary,
        modifier = modifier.semantics { heading() }
    )
}

/** Nhãn nhỏ không bấm được (loại từ, trình độ CEFR, nguồn thuật ngữ): chữ 12sp Medium trên nền tô nhạt, bo 10. */
@Composable
internal fun ReaderTag(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color = Color.Unspecified,
    content: Color = Color.Unspecified
) {
    val colors = LocalCustomColors.current
    Text(
        text = text,
        style = AppText.caption,
        color = content.takeOrElse { colors.textSecondary },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(ChipShape)
            .background(fill.takeOrElse { colors.card })
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/**
 * Một khối cài đặt trong sheet: nhãn 13sp phía trên (+ giá trị [trailing] bên phải, vd. cỡ chữ hiện tại) rồi điều khiển bên dưới.
 * Điều khiển đặt thẳng trên nền sheet (không lồng trong thẻ) để rãnh SegmentedControl vẫn thấy rõ ở giao diện tối.
 */
@Composable
internal fun ReaderSettingBlock(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable () -> Unit
) {
    val colors = LocalCustomColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        // Nhãn thụt 16dp để thẳng hàng với tiêu đề nhóm InsetGroup (chữ trong nhóm); điều khiển giữ nguyên bề rộng đầy đủ
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReaderSectionLabel(label)
            if (trailing != null) {
                Text(text = trailing, style = AppText.secondaryStrong, color = colors.textPrimary)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}
