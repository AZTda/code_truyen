package com.truyencloud.shared.repo

import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.ChapterFlags
import com.truyencloud.shared.data.ENGINE_GOOGLE_FALLBACK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChapterOfflinePolicyTest {
    private fun chapter(
        original: String? = null,
        translated: String? = null,
        engine: String? = "ANTIGRAVITY",
        en: String = "",
        bilingual: String = ""
    ) = Chapter(
        id = "n_1", novelId = "n", chapterNumber = 1, title = "t",
        originalContent = original, translatedContent = translated, translationEngine = engine,
        translatedContentEn = en, translatedContentBilingual = bilingual
    )

    @Test
    fun originalOnlyChapterIsNotOfflineReady() {
        // Bản tải trước thô: có gốc, chưa dịch. Bản cũ coi là "đã tải" nên tải hàng loạt bỏ qua nó.
        val state = ChapterOfflinePolicy.classify(chapter(original = "中文"), requireAi = true)
        assertEquals(ChapterDownloadState.ORIGINAL_ONLY, state)
        assertTrue(ChapterOfflinePolicy.needsFetch(state))
    }

    @Test
    fun translatedChapterIsReady() {
        val state = ChapterOfflinePolicy.classify(chapter(original = "中文", translated = "Tiếng Việt"), requireAi = true)
        assertEquals(ChapterDownloadState.READY, state)
        assertFalse(ChapterOfflinePolicy.needsFetch(state))
    }

    @Test
    fun blankTranslationDoesNotCount() {
        assertEquals(
            ChapterDownloadState.ORIGINAL_ONLY,
            ChapterOfflinePolicy.classify(chapter(original = "中文", translated = "   "), requireAi = false)
        )
        assertEquals(ChapterDownloadState.MISSING, ChapterOfflinePolicy.classify(chapter(), requireAi = false))
        assertEquals(ChapterDownloadState.MISSING, ChapterOfflinePolicy.classify(null, requireAi = false))
    }

    @Test
    fun provisionalTranslationIsUpgradedOnlyWhenLoggedIn() {
        val c = chapter(original = "中文", translated = "Bản tạm", engine = ENGINE_GOOGLE_FALLBACK)
        assertEquals(ChapterDownloadState.PROVISIONAL, ChapterOfflinePolicy.classify(c, requireAi = true))
        // Khách không dịch AI được: bản tạm đã là tốt nhất có thể.
        assertEquals(ChapterDownloadState.READY, ChapterOfflinePolicy.classify(c, requireAi = false))
    }

    @Test
    fun refusalTextIsNotATranslation() {
        val refused = chapter(original = "中文", translated = "Sorry. The prompt could not be submitted.")
        assertTrue(ChapterOfflinePolicy.isRefusalText(refused.translatedContent))
        assertFalse(ChapterOfflinePolicy.hasUsableTranslation(refused))
        assertEquals(ChapterDownloadState.ORIGINAL_ONLY, ChapterOfflinePolicy.classify(refused, requireAi = false))
        assertTrue(ChapterOfflinePolicy.isRefusalText("this may violate Google policies"))
        assertFalse(ChapterOfflinePolicy.isRefusalText(null))
        assertFalse(ChapterOfflinePolicy.isRefusalText("Một chương bình thường"))
    }

    @Test
    fun anyContentCoversOriginalEnglishAndBilingual() {
        assertTrue(ChapterOfflinePolicy.hasAnyContent(chapter(original = "x")))
        assertTrue(ChapterOfflinePolicy.hasAnyContent(chapter(en = "english")))
        assertTrue(ChapterOfflinePolicy.hasAnyContent(chapter(bilingual = "both")))
        assertFalse(ChapterOfflinePolicy.hasAnyContent(chapter()))
    }

    @Test
    fun downloadResultCountsUsableChapters() {
        val r = OfflineDownloadResult(total = 10, ready = 6, provisional = 1, originalOnly = 2, failed = 1)
        assertEquals(7, r.usable)
    }

    @Test
    fun flagsMarkOriginalOnlyAndReady() {
        val originalOnly = ChapterFlags(1, hasOriginal = true, hasTranslation = false, isProvisional = false, hasEnglish = false, hasBilingual = false)
        assertTrue(originalOnly.isOriginalOnly)
        assertFalse(originalOnly.isOfflineReady)

        val ready = ChapterFlags(2, hasOriginal = true, hasTranslation = true, isProvisional = false, hasEnglish = false, hasBilingual = false)
        assertFalse(ready.isOriginalOnly)
        assertTrue(ready.isOfflineReady)

        // Chỉ có bản Anh (không có bản Việt): không phải "chỉ bản gốc" nhưng cũng chưa sẵn sàng ở chế độ Việt.
        val englishOnly = ChapterFlags(3, hasOriginal = true, hasTranslation = false, isProvisional = false, hasEnglish = true, hasBilingual = false)
        assertFalse(englishOnly.isOriginalOnly)
        assertFalse(englishOnly.isOfflineReady)
    }

    @Test
    fun batchSummaryTellsTheTruthAboutOriginalOnlyAndGuests() {
        val mixed = BatchMessages.downloadSummary(
            OfflineDownloadResult(total = 10, ready = 7, provisional = 1, originalOnly = 2, failed = 0), loggedIn = true
        )
        assertTrue(mixed.startsWith("Đã tải 8/10"))
        assertTrue(mixed.contains("2 chương chỉ có bản gốc"))
        assertTrue(mixed.contains("1 chương mới có bản dịch tạm"))
        assertFalse(mixed.contains("Đăng nhập"))

        val guest = BatchMessages.downloadSummary(
            OfflineDownloadResult(total = 5, ready = 5, provisional = 0, originalOnly = 0, failed = 0), loggedIn = false
        )
        assertTrue(guest.contains("Đăng nhập để dịch hàng loạt"))

        val none = BatchMessages.downloadSummary(
            OfflineDownloadResult(total = 4, ready = 0, provisional = 0, originalOnly = 0, failed = 4), loggedIn = true
        )
        assertTrue(none.contains("thất bại"))
    }
}
