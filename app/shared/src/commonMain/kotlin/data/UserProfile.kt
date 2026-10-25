package com.truyencloud.shared.data

import com.truyencloud.shared.api.ServerUrls
import kotlinx.serialization.Serializable

/**
 * Hồ sơ người dùng trên máy. Giá trị mặc định là hồ sơ KHÁCH trung tính — không mang tên/email của ai.
 * Địa chỉ ảnh mặc định do UserRepository suy ra từ địa chỉ máy chủ đang dùng.
 */
@Serializable
data class UserProfile(
    val id: String = "guest",
    val email: String = "",
    val displayName: String = "Độc Giả Khách",
    val bio: String = "",
    val vipTier: String = "",
    val avatarUrl: String = "",
    val bannerUrl: String = "",
    val preferredTheme: String = "system", // "system" (theo hệ điều hành), "dark", "light"; giá trị cũ "oled", "sepia"... vẫn đọc được
    val readerFontSize: Int = 18,
    val readerLineHeight: Float = 1.8f,
    val serverUrl: String = ServerUrls.DEFAULT,
    // Cac so lieu duoi day bat dau tu 0 va do ReadingStats (bang reading_sessions)
    // dien vao. Truoc day chung la hang so trung bay: 48.5 gio / 158 chuong /
    // chuoi 7 ngay, hien y nguyen cho mot nguoi vua cai app xong.
    val totalReadingHours: Float = 0f,
    val totalChaptersRead: Int = 0,
    val totalWordsTranslated: Int = 0,
    val readingStreakDays: Int = 0,
    val authProvider: String = "guest", // "email", "guest"
    val isLoggedIn: Boolean = false,
    val ttsSpeed: Float = 1.0f,
    val ttsAutoScroll: Boolean = true,
    /** Tên hiển thị của hồ sơ giọng đang chọn (luôn khớp [ttsVoiceProfileId]: UserRepository ghi cả hai cùng lúc). */
    val ttsVoiceTone: String = "Mỹ Duyên",
    /** Cài đặt mới: giọng VieNeu nữ mặc định của máy chủ (Nano). Id cũ đã lưu vẫn giữ nguyên. */
    val ttsVoiceProfileId: String = "my_duyen",
    val ttsAutoNextChapter: Boolean = true,
    val ttsBackgroundPlayback: Boolean = true,
    val ttsOnlineMode: Boolean = true,
    val readerPaginated: Boolean = false,
    val readerPageMargin: Int = 16,
    /** Kiểu chữ trình đọc: "serif" | "sans". */
    val readerFontFamily: String = "serif",
    /** Độ sáng phủ của trình đọc (0.15..1.0); 1.0 = không phủ. */
    val readerBrightness: Float = 1.0f,
    /** Giữ màn hình luôn sáng khi đọc (mặc định bật). */
    val readerKeepScreenOn: Boolean = true,
    /** Lật trang / cuộn bằng phím âm lượng (mặc định tắt). */
    val readerVolumeKeyPaging: Boolean = false,
    /** Tài khoản quản trị (từ /auth/me, login, refresh): mới được đổi model và hồ sơ dịch dùng chung. */
    val isAdmin: Boolean = false
)
