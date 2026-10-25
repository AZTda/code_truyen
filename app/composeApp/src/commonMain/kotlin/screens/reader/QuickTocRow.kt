package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.ChapterFlags

/**
 * Một dòng của mục lục nhanh. Cố ý NHẸ (chỉ `clickable` + đường kẻ mảnh, không hiệu ứng nhấn `composed`) để cuộn mượt với 5000
 * chương. Chương đang đọc có nền tô nhạt màu nhấn; huy hiệu lấy từ [ChapterFlags] vì danh sách chương không chứa nội dung, hiện
 * thành một dòng chữ nhỏ dưới tên chương (kèm chữ nên không chỉ dựa vào màu).
 */
@Composable
internal fun QuickTocRow(
    chapter: Chapter,
    isCurrent: Boolean,
    flags: ChapterFlags?,
    onClick: () -> Unit,
    isServerTranslated: Boolean = false
) {
    val colors = LocalCustomColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isCurrent) Modifier.background(colors.accentSoft) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = ReaderSheetInset, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${chapter.chapterNumber}",
                style = AppText.secondaryStrong,
                color = if (isCurrent) colors.onAccentSoft else colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.width(44.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.titleVi.ifBlank { chapter.title.ifBlank { "Chương ${chapter.chapterNumber}" } },
                    style = AppText.body.copy(fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal),
                    color = when {
                        isCurrent -> colors.onAccentSoft
                        chapter.isRead -> colors.textSecondary
                        else -> colors.textPrimary
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TocBadge(flags = flags, isServerTranslated = isServerTranslated)
            }
        }
        HairlineDivider(startInset = ReaderSheetInset)
    }
}

@Composable
private fun TocBadge(flags: ChapterFlags?, isServerTranslated: Boolean) {
    val colors = LocalCustomColors.current
    val (label, tint) = when {
        flags?.isProvisional == true -> "Bản tạm" to colors.warning
        flags?.hasTranslation == true -> "Đã tải (AI)" to colors.success
        isServerTranslated -> "AI đã dịch" to colors.accent
        flags?.isOriginalOnly == true -> "Chỉ bản gốc" to colors.textSecondary
        else -> return
    }
    Text(text = label, style = AppText.caption, color = tint, maxLines = 1)
}
