package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors

// Huy hiệu đặt lên bìa của NovelCard. Nền đặc (màu nền trang 90%) nên đọc được trên mọi ảnh bìa; luôn kèm biểu tượng
// hoặc chữ chứ không chỉ dựa vào màu.

/** Vòng chọn ở góc bìa: rỗng khi chưa chọn, đặc màu nhấn kèm dấu tích khi đã chọn. */
@Composable
internal fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accent else colors.bg.copy(alpha = 0.7f))
            .border(1.5.dp, if (selected) colors.accent else colors.textPrimary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = TruyenCloudIcons.Check,
                contentDescription = "Đã chọn",
                tint = colors.onAccent,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** Chấm tròn 24dp chứa một biểu tượng nhỏ (trái tim yêu thích). */
@Composable
internal fun CoverIconBadge(icon: ImageVector, description: String, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(colors.bg.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = colors.textPrimary, modifier = Modifier.size(14.dp))
    }
}

/** Nhãn "Trong tủ" ở chân bìa cho truyện người dùng đã thêm (Khám phá, tìm kiếm). */
@Composable
internal fun InLibraryBadge(modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    // Bìa nhỏ 96dp: không phóng chữ theo cỡ chữ hệ thống để nhãn không tràn khỏi bìa
    CappedFontScale(max = 1f) {
        Row(
            modifier = modifier
                .clip(ChipShape)
                .background(colors.bg.copy(alpha = 0.92f))
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .semantics(mergeDescendants = true) { contentDescription = "Đã có trong tủ sách" },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(TruyenCloudIcons.Check, contentDescription = null, tint = colors.success, modifier = Modifier.size(12.dp))
            Text(
                text = "Trong tủ",
                style = AppText.caption,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
    }
}

/** Huy hiệu số chương đã dịch / đang dịch ngầm gắn trực tiếp trên bìa bằng icon vector. */
@Composable
internal fun TranslatedCoverBadge(
    translatedChapters: Int,
    overnightEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (translatedChapters <= 0 && !overnightEnabled) return
    val colors = LocalCustomColors.current
    val label = if (translatedChapters > 0) "${translatedChapters}ch" else "Ngầm"
    val desc = when {
        overnightEnabled && translatedChapters > 0 -> "Đang dịch ngầm, đã có $translatedChapters chương dịch"
        overnightEnabled -> "Đang dịch ngầm ban đêm"
        else -> "Đã dịch sẵn $translatedChapters chương"
    }
    CappedFontScale(max = 1f) {
        Row(
            modifier = modifier
                .clip(ChipShape)
                .background(colors.bg.copy(alpha = 0.92f))
                .padding(horizontal = 5.dp, vertical = 2.5.dp)
                .semantics(mergeDescendants = true) { contentDescription = desc },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (overnightEnabled) TruyenCloudIcons.MoonStar else TruyenCloudIcons.Translate,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = label,
                style = AppText.caption,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
    }
}
