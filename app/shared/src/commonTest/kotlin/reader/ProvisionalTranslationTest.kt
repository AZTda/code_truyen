package com.truyencloud.shared.reader

import com.truyencloud.shared.reader.ProvisionalTranslation.UpgradeOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvisionalTranslationTest {

    @Test
    fun fallbackFlagOrGoogleEngineMakesItProvisional() {
        assertTrue(ProvisionalTranslation.isProvisional(isFallback = true, engine = "ANTIGRAVITY"))
        assertTrue(ProvisionalTranslation.isProvisional(isFallback = false, engine = "GOOGLE_FALLBACK"))
        assertTrue(ProvisionalTranslation.isProvisional(isFallback = false, engine = "google_fallback"))
        assertFalse(ProvisionalTranslation.isProvisional(isFallback = false, engine = "ANTIGRAVITY"))
        assertFalse(ProvisionalTranslation.isProvisional(isFallback = false, engine = null))
    }

    @Test
    fun backgroundRetryHappensOncePerChapterPerSession() {
        val policy = ProvisionalRetryPolicy()
        assertTrue(policy.tryBegin("n1", 5, isProvisional = true, isLoggedIn = true))
        assertFalse(policy.tryBegin("n1", 5, isProvisional = true, isLoggedIn = true))
        // chương khác / truyện khác vẫn được thử
        assertTrue(policy.tryBegin("n1", 6, isProvisional = true, isLoggedIn = true))
        assertTrue(policy.tryBegin("n2", 5, isProvisional = true, isLoggedIn = true))
    }

    @Test
    fun guestsAndAiTranslationsNeverRetry() {
        val policy = ProvisionalRetryPolicy()
        assertFalse(policy.tryBegin("n1", 5, isProvisional = true, isLoggedIn = false))
        assertFalse(policy.tryBegin("n1", 5, isProvisional = false, isLoggedIn = true))
        // lượt bị từ chối vì là khách không được "tiêu" cơ hội thử của chương: đăng nhập xong vẫn thử được
        assertTrue(policy.tryBegin("n1", 5, isProvisional = true, isLoggedIn = true))
    }

    @Test
    fun resetAllowsAnotherAttempt() {
        val policy = ProvisionalRetryPolicy()
        assertTrue(policy.tryBegin("n1", 5, true, true))
        policy.reset()
        assertTrue(policy.tryBegin("n1", 5, true, true))
    }

    @Test
    fun upgradeOutcome() {
        assertEquals(
            UpgradeOutcome.SWAP_IN_PLACE,
            ProvisionalTranslation.outcomeOf(resultIsFallback = false, stillOnSameChapter = true, ttsActive = false, showingOriginal = false)
        )
        assertEquals(
            UpgradeOutcome.STILL_PROVISIONAL,
            ProvisionalTranslation.outcomeOf(resultIsFallback = true, stillOnSameChapter = true, ttsActive = false, showingOriginal = false)
        )
        assertEquals(
            UpgradeOutcome.KEEP_CURRENT_VIEW,
            ProvisionalTranslation.outcomeOf(resultIsFallback = false, stillOnSameChapter = false, ttsActive = false, showingOriginal = false)
        )
        assertEquals(
            UpgradeOutcome.KEEP_CURRENT_VIEW,
            ProvisionalTranslation.outcomeOf(resultIsFallback = false, stillOnSameChapter = true, ttsActive = true, showingOriginal = false)
        )
        assertEquals(
            UpgradeOutcome.KEEP_CURRENT_VIEW,
            ProvisionalTranslation.outcomeOf(resultIsFallback = false, stillOnSameChapter = true, ttsActive = false, showingOriginal = true)
        )
    }

    @Test
    fun guestBannerDoesNotBlameBusyAiAndPointsToLogin() {
        val loggedIn = ProvisionalTranslation.bannerMessage(isLoggedIn = true)
        assertTrue("AI đang bận" in loggedIn)
        assertTrue(loggedIn.startsWith(ProvisionalTranslation.BANNER_TEXT))
        val guest = ProvisionalTranslation.bannerMessage(isLoggedIn = false)
        assertFalse("AI đang bận" in guest, "với khách AI không phải là đang bận mà là không dùng được")
        assertTrue("đăng nhập" in guest)
        assertTrue(guest.startsWith(ProvisionalTranslation.BANNER_TEXT))
    }
}
