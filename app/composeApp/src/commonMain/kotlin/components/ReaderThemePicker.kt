package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.compose.theme.ReaderThemeOption
import com.truyencloud.compose.theme.ReaderThemes

/**
 * Hàng ô chọn chủ đề đọc sách (Đen OLED, Than chì, Giấy, Sepia, Rừng thông): mỗi ô là màu nền thật của chủ đề với chữ "Aa" màu chữ
 * thật, nhãn bên dưới. Ô đang chọn có viền màu nhấn 2dp và nhãn đậm hơn. Giá trị [onSelect] nhận là `ReaderThemeOption.mode`.
 */
@Composable
fun ReaderThemePicker(
    selectedMode: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    options: List<ReaderThemeOption> = ReaderThemes.all
) {
    val colors = LocalCustomColors.current
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val selected = option.mode == selectedMode
            Column(
                modifier = Modifier
                    .weight(1f)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option.mode) }),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(CardShape)
                        .background(option.bg)
                        .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.hairline, CardShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aa",
                        color = option.text,
                        fontFamily = ReaderFont,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = option.label,
                    style = AppText.caption,
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
