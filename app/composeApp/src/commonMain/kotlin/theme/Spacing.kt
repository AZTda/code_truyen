package com.truyencloud.compose.theme

import androidx.compose.ui.unit.dp

/** Lưới 4dp: 4 / 8 / 12 / 16 / 20 / 24 / 32. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Lề ngang của trang. */
    val pageH = 20.dp

    /** Lề từ mép màn hình tới nhóm danh sách inset (Cài đặt). */
    val groupInset = 16.dp

    /** Nhịp giữa hai khối nội dung lớn (phần, nhóm). */
    val section = 24.dp

    /** Bìa trong lưới / băng chuyền "Đang đọc": 96x136dp. */
    val coverWidth = 96.dp
    val coverHeight = 136.dp

    /** Hàng điều hướng của thanh trên (chưa tính thanh trạng thái). */
    val topBarHeight = 52.dp

    /** Thanh tab dưới (chưa tính vùng an toàn hệ thống). */
    val bottomBarHeight = 64.dp
}
