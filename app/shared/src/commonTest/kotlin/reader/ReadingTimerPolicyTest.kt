package com.truyencloud.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingTimerPolicyTest {

    @Test
    fun countsWhileReaderIsVisibleAndAppIsInForeground() {
        assertTrue(ReadingTimerPolicy.shouldCount(hasContent = true, readerVisible = true, appForeground = true, ttsPlaying = false))
    }

    @Test
    fun doesNotCountInTheBackgroundOrWhenTheScreenIsOff() {
        assertFalse(ReadingTimerPolicy.shouldCount(hasContent = true, readerVisible = true, appForeground = false, ttsPlaying = false))
    }

    @Test
    fun listeningCountsAsReadingEvenWhenBackgrounded() {
        assertTrue(ReadingTimerPolicy.shouldCount(hasContent = true, readerVisible = true, appForeground = false, ttsPlaying = true))
        assertTrue(ReadingTimerPolicy.shouldCount(hasContent = true, readerVisible = false, appForeground = false, ttsPlaying = true))
    }

    @Test
    fun ttsPausedAndReaderClosedDoesNotCount() {
        assertFalse(ReadingTimerPolicy.shouldCount(hasContent = true, readerVisible = false, appForeground = true, ttsPlaying = false))
    }

    @Test
    fun nothingCountsWithoutContent() {
        assertFalse(ReadingTimerPolicy.shouldCount(hasContent = false, readerVisible = true, appForeground = true, ttsPlaying = true))
    }

    @Test
    fun accumulatorEmitsWholeMinutes() {
        val acc = ReadingTimeAccumulator()
        var minutes = 0
        repeat(11) { minutes += acc.add(ReadingTimerPolicy.SLICE_SECONDS) }
        assertEquals(0, minutes)
        minutes += acc.add(ReadingTimerPolicy.SLICE_SECONDS)
        assertEquals(1, minutes)
        assertEquals(0, acc.pendingSeconds)
        assertEquals(2, acc.add(125))
        assertEquals(5, acc.pendingSeconds)
        assertEquals(0, acc.add(0))
    }
}
