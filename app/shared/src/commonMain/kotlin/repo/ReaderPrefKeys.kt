package com.truyencloud.shared.repo

/**
 * Khoá lưu cài đặt riêng của màn đọc (ngoài bộ khoá chung `PrefKeys`). Kiểu chữ dùng `PrefKeys.READER_FONT_FAMILY`.
 * Tách riêng để không chạm vào tệp khoá chung của phần tài khoản; chỉ `UserRepository` đọc/ghi các khoá này.
 */
internal object ReaderPrefKeys {
    const val BRIGHTNESS = "reader_brightness"
    const val KEEP_SCREEN_ON = "reader_keep_screen_on"
    const val VOLUME_KEY_PAGING = "reader_volume_key_paging"
}
