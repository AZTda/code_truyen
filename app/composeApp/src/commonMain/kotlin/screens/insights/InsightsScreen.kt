package com.truyencloud.compose.screens.insights

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * Tab Thống kê: nối ViewModel với giao diện. Toàn bộ bố cục nằm ở [InsightsContent] để xem trước được mà không cần
 * cơ sở dữ liệu.
 */
@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Mỗi lần mở tab Insights là tính lại số liệu: học / đọc xong quay lại phải thấy số mới, không phải số lúc khởi động.
    LaunchedEffect(Unit) { viewModel.refresh() }

    InsightsContent(state = uiState, modifier = modifier)
}
