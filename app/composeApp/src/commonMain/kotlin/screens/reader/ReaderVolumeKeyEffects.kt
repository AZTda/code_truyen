package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import com.truyencloud.compose.platform.ReaderHardwareKeys
import com.truyencloud.shared.reader.ReaderKeyAction
import com.truyencloud.shared.reader.ReaderVolumeKeys

/**
 * Phím âm lượng khi màn đọc bật tuỳ chọn (Activity chỉ chuyển sự kiện sang [ReaderHardwareKeys] khi cờ bật):
 * chế độ cuộn thì cuộn ~90% chiều cao vùng nhìn.
 */
@Composable
internal fun VolumeKeyScrollEffect(listState: LazyListState, enabled: Boolean) {
    LaunchedEffect(listState, enabled) {
        if (!enabled) return@LaunchedEffect
        ReaderHardwareKeys.events.collect { event ->
            val forward = event == ReaderHardwareKeys.NEXT_PAGE
            when (ReaderVolumeKeys.actionFor(forward, pagedMode = false)) {
                ReaderKeyAction.SCROLL_DOWN, ReaderKeyAction.SCROLL_UP ->
                    listState.animateScrollBy(ReaderVolumeKeys.scrollDistancePx(listState.layoutInfo.viewportSize.height, forward))
                else -> Unit
            }
        }
    }
}

/** Phím âm lượng ở chế độ lật trang: trang sau / trang trước. */
@Composable
internal fun VolumeKeyPageEffect(enabled: Boolean, onAction: (ReaderKeyAction) -> Unit) {
    val latest = rememberUpdatedState(onAction)
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        ReaderHardwareKeys.events.collect { event ->
            latest.value(ReaderVolumeKeys.actionFor(event == ReaderHardwareKeys.NEXT_PAGE, pagedMode = true))
        }
    }
}
