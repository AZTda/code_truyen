package com.truyencloud.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PasswordPolicyTest {
    @Test
    fun acceptsAGoodChange() {
        assertNull(PasswordPolicy.validateChange("oldpass1", "newpass123", "newpass123"))
    }

    @Test
    fun requiresCurrentPassword() {
        assertEquals("Vui lòng nhập mật khẩu hiện tại.", PasswordPolicy.validateChange("", "newpass123", "newpass123"))
    }

    @Test
    fun enforcesMinimumLength() {
        assertEquals("Mật khẩu mới phải có ít nhất 8 ký tự.", PasswordPolicy.validateChange("old", "short7!", "short7!"))
        assertNull(PasswordPolicy.validateChange("old", "exactly8", "exactly8"))
    }

    @Test
    fun rejectsReusingTheSamePassword() {
        assertEquals("Mật khẩu mới phải khác mật khẩu hiện tại.", PasswordPolicy.validateChange("samepass1", "samepass1", "samepass1"))
    }

    @Test
    fun confirmationMustMatch() {
        assertEquals("Mật khẩu nhập lại không khớp.", PasswordPolicy.validateChange("oldpass1", "newpass123", "newpass124"))
    }

    @Test
    fun lengthIsCheckedBeforeMatching() {
        assertEquals("Mật khẩu mới phải có ít nhất 8 ký tự.", PasswordPolicy.validateChange("old", "abc", "xyz"))
    }
}
