package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/** Chiều cao chân trang ở chế độ lật trang (đạt vùng chạm 48dp). ReaderPaginatedView trừ đúng chiều cao này khỏi vùng chữ. */
internal val ReaderPaginatedFooterHeight: Dp = 48.dp

@Composable
internal fun ReaderPaginatedFooter(
    currentPage: Int,
    pageCount: Int,
    currentChapterNumber: Int,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val canGoPrevPage = currentPage > 0
    val canGoPrevChapter = currentPage == 0 && currentChapterNumber > 1
    val canGoNextPage = currentPage < pageCount - 1
    val prevEnabled = canGoPrevPage || canGoPrevChapter

    // Chân trang có chiều cao cố định (vùng chữ đã trừ đi): giới hạn phóng chữ để nhãn không tràn
    CappedFontScale(max = 1.15f) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(ReaderPaginatedFooterHeight),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FooterButton(
                label = if (canGoPrevPage) "Trước" else if (canGoPrevChapter) "Chương trước" else "Trang đầu",
                icon = TruyenCloudIcons.ChevronLeft,
                iconFirst = true,
                enabled = prevEnabled,
                color = if (prevEnabled) colors.textSecondary else colors.textTertiary,
                modifier = Modifier.weight(1f),
                onClick = { if (canGoPrevPage) onPrevPage() else if (canGoPrevChapter) onPrevChapter() }
            )

            Text(
                text = "Trang ${currentPage + 1} / $pageCount",
                style = AppText.caption,
                color = colors.textSecondary,
                maxLines = 1
            )

            FooterButton(
                label = if (canGoNextPage) "Sau" else "Chương sau",
                icon = TruyenCloudIcons.ChevronRight,
                iconFirst = false,
                enabled = true,
                // Chân trang lùi về phía sau chữ truyện: chỉ "Chương sau" (việc chính ở trang cuối) mới dùng màu nhấn
                color = if (canGoNextPage) colors.textSecondary else colors.accent,
                bold = !canGoNextPage,
                modifier = Modifier.weight(1f),
                onClick = { if (canGoNextPage) onNextPage() else onNextChapter() }
            )
        }
    }
}

@Composable
private fun FooterButton(
    label: String,
    icon: ImageVector,
    iconFirst: Boolean,
    enabled: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(ReaderPaginatedFooterHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalArrangement = if (iconFirst) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconFirst) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(2.dp))
        }
        Text(
            text = label,
            style = if (bold) AppText.secondaryStrong.copy(fontWeight = FontWeight.SemiBold) else AppText.secondary,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (!iconFirst) {
            Spacer(Modifier.width(2.dp))
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
    }
}
