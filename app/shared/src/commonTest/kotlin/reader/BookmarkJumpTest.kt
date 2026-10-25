package com.truyencloud.shared.reader

import com.truyencloud.shared.data.Bookmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BookmarkJumpTest {

    private val jump = PendingJump(novelId = "n1", chapterNumber = 50, paragraphIndex = 12)

    private fun view(
        novel: String? = "n1",
        shown: Int? = 50,
        paragraphs: Int = 40,
        loading: Boolean = false,
        loadingTarget: Int? = null,
        translating: Boolean = false,
        restorePending: Boolean = false
    ) = JumpView(novel, shown, paragraphs, loading, loadingTarget, translating, restorePending)

    // ── Nhảy trong cùng chương ──────────────────────────────────────────────

    @Test
    fun jumpsImmediatelyOnlyWhenTheTargetChapterTextIsAlreadyThere() {
        assertTrue(BookmarkJump.canJumpNow(50, shownChapter = 50, paragraphCount = 40))
        assertFalse(BookmarkJump.canJumpNow(50, shownChapter = 49, paragraphCount = 40))
        assertFalse(BookmarkJump.canJumpNow(50, shownChapter = 50, paragraphCount = 0))
        assertFalse(BookmarkJump.canJumpNow(50, shownChapter = null, paragraphCount = 40))
    }

    // ── Nhảy sang chương khác ───────────────────────────────────────────────

    @Test
    fun waitsWhileTheTargetChapterIsStillLoading() {
        // Chương cũ (49) vẫn hiển thị trong lúc chương 50 tải: KHÔNG được nhảy trên đoạn văn của chương cũ
        val step = BookmarkJump.step(jump, view(shown = 49, paragraphs = 30, loading = true, loadingTarget = 50))
        assertEquals(BookmarkJump.Step.Wait, step)
    }

    @Test
    fun appliesOnlyAfterTheTargetParagraphsAreInPlace() {
        val step = BookmarkJump.step(jump, view())
        assertIs<BookmarkJump.Step.Apply>(step)
        assertEquals(ReaderScrollRestore.positionForParagraph(12, 40), step.position)
        assertTrue(step.position > 0f, "luôn > 0 để hiệu ứng khôi phục chạy")
    }

    @Test
    fun waitsUntilTheChaptersOwnSavedPositionHasBeenApplied() {
        // Chương vừa công bố kèm vị trí đã lưu chưa được danh sách cuộn áp dụng: đặt thêm một giá trị nữa sẽ bị lần
        // "khôi phục xong" xoá mất
        assertEquals(BookmarkJump.Step.Wait, BookmarkJump.step(jump, view(restorePending = true)))
        assertIs<BookmarkJump.Step.Apply>(BookmarkJump.step(jump, view(restorePending = false)))
    }

    @Test
    fun jumpUsesTheTargetsParagraphCountNotTheOldChapters() {
        val short = BookmarkJump.step(jump.copy(paragraphIndex = 5), view(paragraphs = 10)) as BookmarkJump.Step.Apply
        assertEquals(ReaderScrollRestore.positionForParagraph(5, 10), short.position)
        val beyond = BookmarkJump.step(jump.copy(paragraphIndex = 999), view(paragraphs = 10)) as BookmarkJump.Step.Apply
        assertEquals(ReaderScrollRestore.positionForParagraph(9, 10), beyond.position, "đoạn vượt quá thì về đoạn cuối")
    }

    // ── Áp dụng ngay trong lượt công bố chương (đường chính) ───────────────

    @Test
    fun chapterReadyAppliesTheJumpInTheSameBreathAsThePublish() {
        val step = BookmarkJump.onChapterReady(jump, novelId = "n1", number = 50, paragraphCount = 40)
        assertIs<BookmarkJump.Step.Apply>(step)
        assertEquals(ReaderScrollRestore.positionForParagraph(12, 40), step.position)
        assertTrue(step.position > 0f)
    }

    @Test
    fun chapterReadyForAnotherChapterOrNovelKeepsWaiting() {
        assertEquals(null, BookmarkJump.onChapterReady(jump, "n1", number = 49, paragraphCount = 40))
        assertEquals(null, BookmarkJump.onChapterReady(jump, "other", number = 50, paragraphCount = 40))
    }

    @Test
    fun chapterReadyWithoutTextDropsTheJumpSoItCannotLeakIntoALaterChapter() {
        // Dịch hỏng / bị huỷ / chương rỗng: bộ tải vẫn báo "xong" với 0 đoạn
        assertEquals(BookmarkJump.Step.Drop, BookmarkJump.onChapterReady(jump, "n1", number = 50, paragraphCount = 0))
    }

    @Test
    fun waitsWhileTheTargetChapterIsBeingTranslated() {
        val step = BookmarkJump.step(jump, view(paragraphs = 0, translating = true))
        assertEquals(BookmarkJump.Step.Wait, step)
    }

    // ── Bỏ việc nhảy khi không còn áp dụng được ─────────────────────────────

    @Test
    fun dropsWhenTheTargetChapterFailedToLoad() {
        assertEquals(BookmarkJump.Step.Drop, BookmarkJump.step(jump, view(shown = 49, loading = false)))
        assertEquals(BookmarkJump.Step.Drop, BookmarkJump.step(jump, view(shown = null, paragraphs = 0, loading = false)))
    }

    @Test
    fun dropsWhenTranslationFailedAndThereIsNoText() {
        assertEquals(BookmarkJump.Step.Drop, BookmarkJump.step(jump, view(paragraphs = 0, translating = false)))
    }

    @Test
    fun dropsWhenTheReaderMovedToAnotherChapterWhileWaiting() {
        val step = BookmarkJump.step(jump, view(shown = 49, loading = true, loadingTarget = 51))
        assertEquals(BookmarkJump.Step.Drop, step, "người đọc bấm sang chương khác: không nhảy nhầm vào chương đó")
    }

    @Test
    fun dropsWhenAnotherNovelIsOpen() {
        assertEquals(BookmarkJump.Step.Drop, BookmarkJump.step(jump, view(novel = "other")))
        assertEquals(BookmarkJump.Step.Drop, BookmarkJump.step(jump, view(novel = null)))
    }

    // ── Danh sách dấu trang theo phạm vi ────────────────────────────────────

    private fun bm(id: String, chapter: Int, para: Int, title: String = "", created: Long = 0, deleted: Long = 0) = Bookmark(
        id = id, novelId = "n1", chapterNumber = chapter, chapterTitle = title, paragraphIndex = para,
        selectedText = "đoạn $para", createdAt = created, deletedAt = deleted
    )

    @Test
    fun scopeChapterShowsOnlyTheCurrentChapter() {
        val all = listOf(bm("a", 1, 0), bm("b", 2, 3), bm("c", 2, 1), bm("d", 3, 0))
        assertEquals(listOf("b", "c"), BookmarkGroups.visible(all, BookmarkScope.CHAPTER, 2).map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), BookmarkGroups.visible(all, BookmarkScope.NOVEL, 2).map { it.id })
    }

    @Test
    fun softDeletedBookmarksNeverShow() {
        val all = listOf(bm("a", 1, 0), bm("gone", 1, 1, deleted = 99))
        assertEquals(listOf("a"), BookmarkGroups.visible(all, BookmarkScope.NOVEL, 1).map { it.id })
        assertEquals(listOf("a"), BookmarkGroups.visible(all, BookmarkScope.CHAPTER, 1).map { it.id })
    }

    @Test
    fun groupsAreOrderedByChapterAndBookmarksByParagraph() {
        val groups = BookmarkGroups.group(
            listOf(bm("x", 7, 5, "Tên bảy"), bm("y", 2, 9), bm("z", 7, 1, "Tên bảy"), bm("w", 2, 0, "Hai"))
        )
        assertEquals(listOf(2, 7), groups.map { it.chapterNumber })
        assertEquals(listOf("w", "y"), groups[0].bookmarks.map { it.id })
        assertEquals(listOf("z", "x"), groups[1].bookmarks.map { it.id })
        assertEquals("Hai", groups[0].title, "tiêu đề lấy từ dấu trang đầu tiên có tiêu đề")
    }

    @Test
    fun groupLabelDoesNotRepeatTheChapterNumber() {
        assertEquals("Chương 12 • Phá Trận", BookmarkGroup(12, "Phá Trận", emptyList()).label)
        assertEquals("Chương 12: Phá Trận", BookmarkGroup(12, "Chương 12: Phá Trận", emptyList()).label)
        assertEquals("第12章 破阵", BookmarkGroup(12, "第12章 破阵", emptyList()).label)
        assertEquals("Chương 12", BookmarkGroup(12, "  ", emptyList()).label)
    }

    @Test
    fun summaryAndExport() {
        val groups = BookmarkGroups.group(listOf(bm("a", 1, 0, "Mở đầu"), bm("b", 3, 2, "Giữa"), bm("c", 3, 4, "Giữa")))
        assertEquals("3 dấu trang • 2 chương", BookmarkGroups.summary(BookmarkScope.NOVEL, groups, 1))
        assertEquals("Chương 3 • 2 đoạn đánh dấu", BookmarkGroups.summary(BookmarkScope.CHAPTER, groups.drop(1), 3))
        val (file, md) = BookmarkGroups.exportMarkdown("Vạn Cổ", groups)
        assertEquals("V_n_C__bookmarks.md", file)
        assertTrue(md.startsWith("# Vạn Cổ"))
        assertTrue("## Chương 1 • Mở đầu" in md && "## Chương 3 • Giữa" in md)
        assertTrue("> đoạn 4" in md)
    }
}
