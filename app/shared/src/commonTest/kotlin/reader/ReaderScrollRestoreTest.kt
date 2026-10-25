package com.truyencloud.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderScrollRestoreTest {
    private val none = ReaderScrollRestore.Decision.None

    @Test
    fun unreadChapterOpensAtTheTopInsteadOfKeepingThePreviousDepth() {
        // Lỗi gốc: vị trí đã lưu = 0 thì không khôi phục gì, danh sách giữ độ sâu của chương trước.
        assertEquals(
            ReaderScrollRestore.Decision.ScrollTo(0),
            ReaderScrollRestore.decide(savedPosition = 0f, itemCount = 52, alreadyRestored = false)
        )
    }

    @Test
    fun restoreHappensOnlyOncePerChapterWhenThereIsNothingSaved() {
        // Sau khi khôi phục xong, onScrollRestored() đặt lại vị trí về 0: lần chạy lại KHÔNG được cuộn về đầu.
        assertEquals(none, ReaderScrollRestore.decide(0f, 52, alreadyRestored = true))
    }

    @Test
    fun waitsForContentBeforeDeciding() {
        assertEquals(none, ReaderScrollRestore.decide(0.5f, 0, alreadyRestored = false))
        assertEquals(none, ReaderScrollRestore.decide(0f, 0, alreadyRestored = false))
    }

    @Test
    fun explicitJumpStillScrollsEvenAfterTheInitialRestore() {
        val d = ReaderScrollRestore.decide(0.5f, 101, alreadyRestored = true)
        assertEquals(ReaderScrollRestore.Decision.ScrollTo(50), d)
    }

    @Test
    fun saveAndRestoreAreInverseSoReopeningDoesNotDrift() {
        for (count in listOf(2, 3, 10, 52, 101, 800)) {
            for (index in 0 until count) {
                val position = ReaderScrollRestore.positionFor(index, count)
                assertEquals(index, ReaderScrollRestore.indexFor(position, count), "count=$count index=$index")
            }
        }
    }

    @Test
    fun positionsAreClamped() {
        assertEquals(0, ReaderScrollRestore.indexFor(-1f, 10))
        assertEquals(9, ReaderScrollRestore.indexFor(5f, 10))
        assertEquals(0, ReaderScrollRestore.indexFor(0.7f, 1))
        assertEquals(0f, ReaderScrollRestore.positionFor(3, 1))
    }

    @Test
    fun jumpToParagraphLandsOnThatParagraphNotTheOneAbove() {
        val paragraphs = 40
        val itemCount = ReaderScrollRestore.listItemCount(paragraphs)
        assertEquals(42, itemCount)
        for (p in listOf(0, 1, 17, 39)) {
            val position = ReaderScrollRestore.positionForParagraph(p, paragraphs)
            assertTrue(position > 0f, "luôn > 0 để hiệu ứng khôi phục chạy")
            // mục 0 là tiêu đề nên đoạn p nằm ở mục p + 1
            assertEquals(p + 1, ReaderScrollRestore.indexFor(position, itemCount))
        }
        assertEquals(0f, ReaderScrollRestore.positionForParagraph(3, 0))
    }

    @Test
    fun pagedRestoreUsesTheSameRounding() {
        assertEquals(0, ReaderScrollRestore.pageFor(0f, 8))
        assertEquals(7, ReaderScrollRestore.pageFor(1f, 8))
        assertEquals(3, ReaderScrollRestore.pageFor(3f / 7f, 8))
    }

    @Test
    fun replacingTextWithTheSameParagraphCountNeedsNoRestore() {
        // Khoá mục theo chỉ số: danh sách tự giữ chỗ khi chữ đổi mà số đoạn không đổi
        assertEquals(0f, ReaderScrollRestore.placeAfterReplace(oldCount = 40, newCount = 40, scrollFraction = 0.6f))
    }

    @Test
    fun replacingTextWithAnotherParagraphCountKeepsTheReadingFraction() {
        assertEquals(0.6f, ReaderScrollRestore.placeAfterReplace(oldCount = 40, newCount = 52, scrollFraction = 0.6f), 0.0001f)
        assertEquals(1f, ReaderScrollRestore.placeAfterReplace(40, 52, 7f), 0.0001f)
    }

    @Test
    fun replacingTextAtTheTopStillTriggersARestoreToTheTop() {
        val place = ReaderScrollRestore.placeAfterReplace(oldCount = 40, newCount = 52, scrollFraction = 0f)
        assertTrue(place > 0f, "luôn > 0 để hiệu ứng khôi phục chạy")
        // ...và quy về đúng mục đầu / trang đầu, không trôi đi chỗ khác
        assertEquals(0, ReaderScrollRestore.indexFor(place, ReaderScrollRestore.listItemCount(52)))
        assertEquals(0, ReaderScrollRestore.pageFor(place, 12))
    }

    @Test
    fun progressIsIgnoredUntilTheNewChapterIsSettled() {
        // Vừa đổi sang chương B nhưng vị trí ban đầu chỉ mới được đặt cho chương A
        assertFalse(ReaderScrollRestore.shouldReport(settledKey = "n_1", currentKey = "n_2"))
        assertFalse(ReaderScrollRestore.shouldReport(settledKey = null, currentKey = "n_2"))
        assertTrue(ReaderScrollRestore.shouldReport(settledKey = "n_2", currentKey = "n_2"))
    }
}
