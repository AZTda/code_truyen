package com.truyencloud.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderVolumeKeysTest {

    @Test
    fun pagedModeTurnsPages() {
        assertEquals(ReaderKeyAction.NEXT_PAGE, ReaderVolumeKeys.actionFor(forward = true, pagedMode = true))
        assertEquals(ReaderKeyAction.PREVIOUS_PAGE, ReaderVolumeKeys.actionFor(forward = false, pagedMode = true))
    }

    @Test
    fun scrollModeScrollsTheViewport() {
        assertEquals(ReaderKeyAction.SCROLL_DOWN, ReaderVolumeKeys.actionFor(forward = true, pagedMode = false))
        assertEquals(ReaderKeyAction.SCROLL_UP, ReaderVolumeKeys.actionFor(forward = false, pagedMode = false))
    }

    @Test
    fun scrollDistanceIsAboutNinetyPercentOfTheViewport() {
        assertEquals(1800f, ReaderVolumeKeys.scrollDistancePx(2000, forward = true), 0.01f)
        assertEquals(-900f, ReaderVolumeKeys.scrollDistancePx(1000, forward = false), 0.01f)
        assertEquals(0f, ReaderVolumeKeys.scrollDistancePx(-5, forward = true), 0.01f)
    }

    @Test
    fun keysPageTheBookOnlyWhenOnTopWithTextAndEnabled() {
        assertTrue(ReaderVolumeKeys.pagingActive(optionOn = true, overlayOpen = false, hasText = true))
        assertFalse(ReaderVolumeKeys.pagingActive(optionOn = false, overlayOpen = false, hasText = true))
    }

    @Test
    fun anyOverlayLeavesTheKeysToTheSystemVolume() {
        // Bảng Trợ lý (gõ câu hỏi), Audiobook (thanh âm lượng), bảng dấu trang... đều che phía trước: không lật cuốn sách phía sau
        assertFalse(ReaderVolumeKeys.pagingActive(optionOn = true, overlayOpen = true, hasText = true))
    }

    @Test
    fun noTextMeansNothingToPage() {
        // Thẻ chờ / thẻ lỗi dịch / thẻ lỗi tải chương: không có gì để lật nên không nuốt phím âm lượng
        assertFalse(ReaderVolumeKeys.pagingActive(optionOn = true, overlayOpen = false, hasText = false))
    }
}
