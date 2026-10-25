package com.truyencloud.compose.screens.home

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Vòng xoay kéo-để-làm-mới của Tủ sách / Khám phá, màu theo bảng màu hiện tại.
 *
 * Cử chỉ kéo (`Modifier.pullToRefresh`) được gắn ở BAO NGOÀI LargeTitleScaffold, còn vòng xoay này đặt trong vùng nội
 * dung dưới thanh tiêu đề. Nếu gắn cử chỉ ở trong nội dung (PullToRefreshBox) thì nó nằm gần danh sách hơn thanh tiêu đề
 * và nuốt hết phần kéo xuống dư ra: tiêu đề lớn đã thu lại sẽ không bao giờ mở ra được. Gắn ở ngoài thì thanh tiêu đề
 * mở ra trước, phần dư mới kéo ra vòng xoay.
 *
 * Bao nội dung bằng `clipToBounds()` để vòng xoay (xuất phát phía trên mép) không loé lên trên thanh tiêu đề.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    PullToRefreshDefaults.Indicator(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier,
        containerColor = colors.cardHigh,
        color = colors.accent
    )
}
