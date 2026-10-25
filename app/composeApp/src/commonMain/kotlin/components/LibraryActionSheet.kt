package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayTitle
import com.truyencloud.shared.library.LibraryListLogic
import com.truyencloud.shared.library.LibraryShelves

/**
 * Tờ hành động của Tủ sách cho MỘT hoặc NHIỀU truyện: chuyển kệ, yêu thích, xóa khỏi tủ. Mở khi nhấn giữ một bìa truyện
 * (truyện đó được chọn sẵn) hoặc từ thanh chọn nhiều.
 *
 * @param onSelectMore null = ẩn dòng "Chọn thêm truyện" (đã chọn hết mọi truyện đang hiển thị).
 */
@Composable
fun LibraryActionSheet(
    novels: List<Novel>,
    onDismiss: () -> Unit,
    onMoveToShelf: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onRemove: () -> Unit,
    onSelectMore: (() -> Unit)? = null
) {
    if (novels.isEmpty()) return
    val colors = LocalCustomColors.current
    val allFavorite = novels.all { it.isFavorite }

    IosSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageH)
        ) {
            SheetHeader(novels = novels)
            Spacer(modifier = Modifier.height(Spacing.md))

            SectionTitle("Chuyển sang kệ")
            LibraryShelves.all.forEach { shelf ->
                val isHere = novels.all { LibraryListLogic.shelfOf(it) == shelf.id }
                SheetRow(
                    icon = if (isHere) TruyenCloudIcons.Check else TruyenCloudIcons.Bookshelf,
                    title = shelf.label,
                    subtitle = if (isHere) "Đang ở kệ này" else shelf.hint,
                    tint = if (isHere) colors.accent else colors.textSecondary,
                    enabled = !isHere,
                    onClick = { onMoveToShelf(shelf.id) }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.sm))
            SectionTitle("Khác")
            SheetRow(
                icon = if (allFavorite) TruyenCloudIcons.HeartFilled else TruyenCloudIcons.Heart,
                title = if (allFavorite) "Bỏ yêu thích" else "Thêm vào yêu thích",
                tint = colors.textSecondary,
                onClick = onToggleFavorite
            )
            if (onSelectMore != null) {
                SheetRow(
                    icon = TruyenCloudIcons.CheckCircle,
                    title = "Chọn thêm truyện",
                    subtitle = "Chạm vào các bìa để chọn nhiều truyện cùng lúc",
                    tint = colors.textSecondary,
                    onClick = onSelectMore
                )
            }
            SheetRow(
                icon = TruyenCloudIcons.Delete,
                title = "Xóa khỏi tủ sách",
                subtitle = "Có thể xóa luôn dữ liệu đọc offline",
                tint = colors.error,
                titleColor = colors.error,
                onClick = onRemove
            )
        }
    }
}

@Composable
private fun SheetHeader(novels: List<Novel>) {
    val colors = LocalCustomColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (novels.size == 1) {
            val novel = novels.first()
            NovelCoverImage(
                novel = novel,
                modifier = Modifier.width(40.dp).height(56.dp),
                artScale = 0.4f
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = novel.displayTitle,
                    style = AppText.bookTitle.copy(fontSize = 17.sp, lineHeight = 22.sp),
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = progressLine(novel),
                    style = AppText.secondary,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.accentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(TruyenCloudIcons.CheckCircle, contentDescription = null, tint = colors.onAccentSoft, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "${novels.size} truyện đã chọn", style = AppText.sectionHeader, color = colors.textPrimary)
                Text(
                    text = "Thao tác áp dụng cho tất cả truyện đã chọn",
                    style = AppText.secondary,
                    color = colors.textSecondary
                )
            }
        }
    }
}

private fun progressLine(novel: Novel): String {
    val shelf = LibraryShelves.label(novel.shelfName)
    return when {
        novel.lastReadChapter > 0 && novel.totalChapters > 0 -> "$shelf · Chương ${novel.lastReadChapter}/${novel.totalChapters}"
        novel.lastReadChapter > 0 -> "$shelf · Chương ${novel.lastReadChapter}"
        else -> "$shelf · Chưa đọc"
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = AppText.groupHeader,
        color = LocalCustomColors.current.textSecondary,
        modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xs)
    )
}

@Composable
private fun SheetRow(
    title: String,
    tint: Color,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector,
    titleColor: Color = Color.Unspecified,
    enabled: Boolean = true
) {
    val colors = LocalCustomColors.current
    val titleTint = if (titleColor == Color.Unspecified) colors.textPrimary else titleColor
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(ChipShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = AppText.body,
                color = if (enabled) titleTint else colors.textSecondary
            )
            if (subtitle != null) {
                Text(text = subtitle, style = AppText.secondary, color = colors.textSecondary)
            }
        }
    }
}
