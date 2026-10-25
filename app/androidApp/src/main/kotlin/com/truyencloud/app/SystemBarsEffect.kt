package com.truyencloud.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.toArgb
import com.truyencloud.compose.theme.AppColors

/**
 * Màu biểu tượng thanh trạng thái / thanh điều hướng đi theo CHỦ ĐỀ CỦA ỨNG DỤNG, không theo chế độ tối của hệ thống.
 *
 * `enableEdgeToEdge()` mặc định chọn theo hệ thống nên: chủ đề tối của app trên máy đang ở chế độ sáng cho
 * biểu tượng đen trên nền đen (mất giờ, pin, sóng), còn chủ đề sáng/sepia/giấy trên máy chế độ tối thì ngược lại.
 * Chế độ "system" (mặc định của bản cài mới) đi theo hệ điều hành.
 *
 * Cùng lúc đặt nền cửa sổ theo chủ đề của app: chủ đề trong app khác chế độ của máy thì nền theme khởi động
 * (res/values[-night]/themes.xml) sẽ lộ ra phía sau khi bàn phím mở hoặc xoay màn hình.
 */
@Composable
fun ComponentActivity.SystemBarsEffect(preferredTheme: String) {
    val systemDark = isSystemInDarkTheme()
    val lightBackground = when (preferredTheme) {
        "light", "sepia", "parchment" -> true
        "system" -> !systemDark
        else -> false
    }
    DisposableEffect(lightBackground) {
        // light(): nền sáng → biểu tượng TỐI; dark(): nền tối → biểu tượng SÁNG
        val style = if (lightBackground) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.setBackgroundDrawable(ColorDrawable((if (lightBackground) AppColors.lightBg else AppColors.darkBg).toArgb()))
        onDispose {}
    }
}
