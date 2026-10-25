package com.truyencloud.compose.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.SmallTitleTopBar
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Thanh trên cùng khi đang chọn nhiều truyện, thay cho tiêu đề lớn "Tủ sách": "Đã chọn N", nút quay lại thoát chế độ chọn,
 * rồi chọn tất cả / yêu thích / chuyển kệ / xóa. Nút Back của hệ thống cũng thoát chế độ chọn.
 *
 * @param allFavorite mọi truyện đang chọn đã yêu thích: nút trái tim sẽ BỎ yêu thích (vẽ tim đặc).
 */
@Composable
fun LibrarySelectionBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onFavorite: () -> Unit,
    onMoveShelf: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    allFavorite: Boolean = false
) {
    val colors = LocalCustomColors.current
    SmallTitleTopBar(
        title = "Đã chọn $count",
        modifier = modifier,
        onBack = onClose,
        backContentDescription = "Thoát chế độ chọn",
        actions = {
            QuietIconButton(
                icon = TruyenCloudIcons.CheckCircle,
                contentDescription = if (allSelected) "Bỏ chọn tất cả" else "Chọn tất cả",
                onClick = onToggleSelectAll,
                tint = if (allSelected) colors.accent else colors.textSecondary
            )
            QuietIconButton(
                icon = if (allFavorite) TruyenCloudIcons.HeartFilled else TruyenCloudIcons.Heart,
                contentDescription = if (allFavorite) "Bỏ yêu thích" else "Thêm vào yêu thích",
                onClick = onFavorite
            )
            QuietIconButton(TruyenCloudIcons.FolderSpecial, "Chuyển kệ", onMoveShelf)
            QuietIconButton(TruyenCloudIcons.Delete, "Xóa khỏi tủ sách", onDelete, tint = colors.error)
        }
    )
}
