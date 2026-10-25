package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Đăng nhập và tài khoản. */
interface AuthAccountIcons {
    val Lock: ImageVector get() = AuthHolder.lock
    val Email: ImageVector get() = AuthHolder.email
    val Visibility: ImageVector get() = AuthHolder.visibility
    val VisibilityOff: ImageVector get() = AuthHolder.visibilityOff
}

private const val EYE = "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z"

private object AuthHolder {
    val lock by lazyIcon(
        "Lock",
        "M7 11V8a5 5 0 0 1 10 0v3",
        "M6.5 11h11a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5v-7A1.5 1.5 0 0 1 6.5 11Z",
        "M12 15v2.5"
    )
    val email by lazyIcon(
        "Email",
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z", "M3.5 7.5l8.5 6 8.5-6"
    )
    val visibility by lazyIcon("Visibility", EYE, "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z")
    val visibilityOff by lazyIcon("VisibilityOff", EYE, "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z", "M4 4l16 16")
}
