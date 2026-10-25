package com.truyencloud.compose.screens.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.SegmentedControl

enum class DetailTab(val label: String) {
    SYNOPSIS("Giới thiệu"),
    CHAPTERS("Mục lục")
}

/** Chuyển giữa [Giới thiệu] và [Mục lục (N)] bằng ô chọn kiểu iOS. Lề ngang do nơi gọi đặt. */
@Composable
fun DetailTabSwitcher(
    selectedTab: DetailTab,
    chapterCount: Int,
    onSelect: (DetailTab) -> Unit,
    modifier: Modifier = Modifier
) {
    SegmentedControl(
        items = DetailTab.entries,
        selected = selectedTab,
        onSelect = onSelect,
        label = { tab -> if (tab == DetailTab.CHAPTERS) "${tab.label} ($chapterCount)" else tab.label },
        modifier = modifier
    )
}
