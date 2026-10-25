package com.truyencloud.compose.screens.insights

import androidx.compose.ui.graphics.Color
import com.truyencloud.compose.theme.AppPalette

/**
 * Màu theo chủ đề của mạng lưới từ. Lấy từ bảng màu của giao diện (đạt tương phản trên cả nền sáng lẫn tối) thay cho mã
 * hex máy chủ gửi kèm; chủ đề lạ dùng màu trung tính. Tên chủ đề luôn hiện bằng chữ (chip lọc, thẻ chi tiết) nên màu chỉ là
 * dấu phụ trợ.
 */
internal fun clusterColor(cluster: String, colors: AppPalette): Color = when (cluster) {
    "Time & Transience" -> colors.warning
    "Martial & Power" -> colors.error
    "Emotion & Mind" -> colors.success
    "Arcane & Mystery" -> colors.textPrimary
    "Nature & Cosmos" -> colors.accent
    else -> colors.textTertiary
}
