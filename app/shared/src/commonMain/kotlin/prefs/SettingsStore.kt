package com.truyencloud.shared.prefs

/**
 * Kho lưu cài đặt bền vững, dùng chung cho Android và iOS.
 *
 * VÌ SAO CẦN:
 * Trước đây `UserRepository` giữ toàn bộ hồ sơ người dùng trong một
 * `MutableStateFlow` CHỈ NẰM TRONG RAM. Không có DataStore, không
 * SharedPreferences, không NSUserDefaults, và `UserProfile` cũng không phải
 * entity Room. Hệ quả: chủ đề, cỡ chữ, giãn dòng, tốc độ đọc, hồ sơ giọng,
 * URL máy chủ, tên hiển thị, tiểu sử — tất cả reset về mặc định mỗi lần mở
 * lại ứng dụng. Người dùng chỉnh giọng đọc ưng ý xong, hôm sau phải chỉnh lại
 * từ đầu.
 *
 * Cài đặt bằng `expect/actual` trực tiếp trên API nền tảng để không phải thêm
 * phụ thuộc ngoài: SharedPreferences (Android) và NSUserDefaults (iOS).
 */
expect class SettingsStore {
    fun getString(key: String, default: String): String
    fun getInt(key: String, default: Int): Int
    fun getFloat(key: String, default: Float): Float
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getLong(key: String, default: Long): Long

    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun putFloat(key: String, value: Float)
    fun putBoolean(key: String, value: Boolean)
    fun putLong(key: String, value: Long)

    fun remove(key: String)
    fun clear()

    /**
     * Bí mật (token đăng nhập) — lưu MÃ HOÁ, không đi chung kho với các cài đặt thường.
     * Android: Keystore AES-GCM trong tệp `truyencloud_secure_prefs`. iOS: Keychain.
     * Đọc không thấy hoặc giải mã hỏng thì trả chuỗi rỗng (coi như chưa đăng nhập).
     */
    fun getSecret(key: String): String
    fun putSecret(key: String, value: String)
    fun removeSecret(key: String)
}

/** Tạo kho lưu cài đặt cho nền tảng hiện tại. */
expect fun createSettingsStore(): SettingsStore

/** Tên khoá — gom một chỗ để tránh gõ nhầm chuỗi rải rác trong mã nguồn. */
object PrefKeys {
    // Hồ sơ
    const val USER_ID = "user_id"
    const val EMAIL = "email"
    const val DISPLAY_NAME = "display_name"
    const val BIO = "bio"
    const val VIP_TIER = "vip_tier"
    const val AVATAR_URL = "avatar_url"
    const val BANNER_URL = "banner_url"
    const val AUTH_PROVIDER = "auth_provider"
    const val IS_LOGGED_IN = "is_logged_in"
    const val IS_ADMIN = "is_admin"

    /**
     * Lưu qua [SettingsStore.putSecret] (mã hoá). Mã cũ vẫn gọi getString/putString/remove với khoá này thì
     * các actual tự chuyển hướng sang kho mã hoá, nên không bao giờ có bản rõ nằm trong cài đặt thường.
     */
    const val AUTH_TOKEN = "auth_token"

    /** Id tài khoản đăng nhập gần nhất — để phát hiện đổi tài khoản và xoá dữ liệu người trước. */
    const val LAST_USER_ID = "last_user_id"

    /** Mốc (epoch ms) gia hạn token gần nhất; gia hạn tối đa mỗi 24 giờ. */
    const val LAST_TOKEN_REFRESH_AT = "last_token_refresh_at"

    // Lần chạy đầu
    const val WELCOME_SEEN = "welcome_guide_seen"
    const val LOGIN_OFFER_SHOWN = "login_offer_shown"

    /** Nội dung mẫu (truyện/từ vựng demo) chỉ gieo MỘT lần, không gieo lại sau khi người dùng xoá. */
    const val SAMPLE_CONTENT_SEEDED = "sample_content_seeded"

    // Giao diện & trình đọc
    const val THEME = "preferred_theme"
    const val READER_FONT_SIZE = "reader_font_size"
    const val READER_LINE_HEIGHT = "reader_line_height"
    const val READER_FONT_FAMILY = "reader_font_family"
    const val READER_PAGINATED = "reader_paginated"
    const val READER_PAGE_MARGIN = "reader_page_margin"

    // Máy chủ
    const val SERVER_URL = "server_url"

    // Sách nói
    const val TTS_SPEED = "tts_speed"
    const val TTS_AUTO_SCROLL = "tts_auto_scroll"
    const val TTS_VOICE_TONE = "tts_voice_tone"
    const val TTS_VOICE_PROFILE = "tts_voice_profile_id"
    const val TTS_AUTO_NEXT = "tts_auto_next_chapter"
    const val TTS_BACKGROUND = "tts_background_playback"
    const val TTS_ONLINE = "tts_online_mode"

    // Học tiếng Anh
    const val ENGLISH_LEVEL = "english_level"
    const val NEW_CARDS_PER_DAY = "new_cards_per_day"
    const val MAX_REVIEWS_PER_DAY = "max_reviews_per_day"
    const val DESIRED_RETENTION = "desired_retention"

    // Bao chuong moi
    const val NOTIFY_NEW_CHAPTERS = "notify_new_chapters"
    const val LAST_UPDATE_CHECK_AT = "last_update_check_at"
    /** Tien to: so chuong da bao cho tung truyen, de khong bao lai cung mot chuong. */
    const val NOTIFIED_CHAPTERS_PREFIX = "notified_chapters_"

    // Tìm kiếm: dữ liệu gắn với MỘT tài khoản, bị xoá cùng dữ liệu tài khoản đó (xem SearchHistoryStore)
    /** JSON `{"owner":"<id tài khoản>","queries":[...]}`: tìm gần đây; đọc bằng id khác chủ thì bị bỏ. */
    const val SEARCH_HISTORY = "search_history"
}
