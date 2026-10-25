package com.truyencloud.compose.theme

/**
 * Chế độ giao diện của ứng dụng, dùng cho ô chọn Hệ thống / Sáng / Tối trong Cài đặt. Lưu trong
 * `UserProfile.preferredTheme` dưới dạng chuỗi [stored], cùng khoá với các giá trị cũ ("dark", "light") nên
 * không phải đổi kho lưu.
 *
 * ```
 * SegmentedControl(ThemeMode.options.map { it.label }, ThemeMode.fromStored(profile.preferredTheme).ordinal) { i ->
 *     viewModel.setTheme(ThemeMode.options[i].stored)
 * }
 * ```
 */
enum class ThemeMode(val stored: String, val label: String) {
    System("system", "Hệ thống"),
    Light("light", "Sáng"),
    Dark("dark", "Tối");

    companion object {
        /** Máy mới theo hệ điều hành: tối sau hoàng hôn, sáng ban ngày. */
        const val DEFAULT_STORED: String = "system"

        /** Thứ tự hiển thị trong ô chọn. */
        val options: List<ThemeMode> = entries

        /**
         * Đổi giá trị đã lưu về chế độ hiển thị. Giá trị cũ của các chủ đề đọc sách ("oled", "slate", "forest" tối;
         * "sepia", "parchment" sáng) được xếp theo độ sáng nền; giá trị lạ hoặc rỗng thì theo hệ thống.
         */
        fun fromStored(value: String?): ThemeMode = when (value) {
            "system" -> System
            "light", "sepia", "parchment" -> Light
            "dark", "oled", "slate", "forest" -> Dark
            else -> System
        }
    }
}
