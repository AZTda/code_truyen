package com.truyencloud.shared.reader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionSessionOwnerTest {

    @Test
    fun theFirstOpenOwnsNothingYet() {
        assertFalse(CompanionSessionOwner().claim("n1", "u1"))
    }

    @Test
    fun reopeningTheSameNovelAsTheSameAccountKeepsTheConversation() {
        val owner = CompanionSessionOwner()
        owner.claim("n1", "u1")
        assertFalse(owner.claim("n1", "u1"))
        assertFalse(owner.claim("n1", "u1"))
    }

    @Test
    fun openingAnotherNovelDropsTheConversation() {
        val owner = CompanionSessionOwner()
        owner.claim("n1", "u1")
        assertTrue(owner.claim("n2", "u1"), "hội thoại của truyện A không được sang truyện B")
        assertFalse(owner.claim("n2", "u1"), "chỉ bỏ MỘT lần")
    }

    @Test
    fun anotherAccountOnTheSameNovelDropsTheConversation() {
        val owner = CompanionSessionOwner()
        owner.claim("n1", "u1")
        assertTrue(owner.claim("n1", "u2"), "đăng xuất rồi đăng nhập tài khoản khác không được thấy hội thoại cũ")
        assertFalse(owner.claim("n1", "u2"))
    }

    @Test
    fun guestAndLoggedInAreDifferentAccounts() {
        val owner = CompanionSessionOwner()
        owner.claim("n1", "guest")
        assertTrue(owner.claim("n1", "u1"))
        assertTrue(owner.claim("n1", "guest"))
    }

    @Test
    fun returningToTheFirstNovelAfterAnotherStartsFresh() {
        val owner = CompanionSessionOwner()
        owner.claim("n1", "u1")
        owner.claim("n2", "u1")
        assertTrue(owner.claim("n1", "u1"))
    }
}
