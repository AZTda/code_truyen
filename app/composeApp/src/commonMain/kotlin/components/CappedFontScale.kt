package com.truyencloud.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Giới hạn hệ số phóng chữ của hệ thống cho phần có kích thước cố định (chữ trên bìa, rãnh SegmentedControl cao
 * 36dp, nhãn thanh tab). Để nguyên thì người chỉnh chữ lớn thấy nhãn bị ngắt dòng / cắt cụt; phần nội dung chính vẫn phóng theo
 * cài đặt.
 */
@Composable
fun CappedFontScale(max: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= max) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, max), content = content)
    }
}
