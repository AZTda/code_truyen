package com.truyencloud.shared.notify

import com.truyencloud.shared.prefs.SettingsStore
import com.truyencloud.shared.srs.StudyDay
import com.truyencloud.shared.srs.platformUtcOffsetMillis

/** Nhắc ôn từ vựng hằng ngày: bật/tắt và giờ nhắc (mặc định 20:00). */
data class ReviewReminderConfig(
    val enabled: Boolean = false,
    val hour: Int = DEFAULT_HOUR,
    val minute: Int = 0
) {
    val timeLabel: String get() = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

    companion object {
        const val DEFAULT_HOUR = 20
    }
}

/** Lưu cấu hình nhắc nhở vào kho cài đặt bền vững (khoá riêng, không đụng PrefKeys của màn Cài đặt). */
object ReviewReminderPrefs {
    const val KEY_ENABLED = "review_reminder_enabled"
    const val KEY_HOUR = "review_reminder_hour"
    const val KEY_MINUTE = "review_reminder_minute"

    fun load(store: SettingsStore): ReviewReminderConfig = ReviewReminderConfig(
        enabled = store.getBoolean(KEY_ENABLED, false),
        hour = store.getInt(KEY_HOUR, ReviewReminderConfig.DEFAULT_HOUR).coerceIn(0, 23),
        minute = store.getInt(KEY_MINUTE, 0).coerceIn(0, 59)
    )

    fun save(store: SettingsStore, config: ReviewReminderConfig) {
        store.putBoolean(KEY_ENABLED, config.enabled)
        store.putInt(KEY_HOUR, config.hour.coerceIn(0, 23))
        store.putInt(KEY_MINUTE, config.minute.coerceIn(0, 59))
    }
}

object ReviewReminderMath {
    private const val DAY_MS = 86_400_000L

    /**
     * Lần nhắc kế tiếp lúc [hour]:[minute] giờ địa phương — LUÔN sau [nowEpoch] (đã qua giờ hôm nay thì là ngày mai).
     * [offsetAt] nhận thời điểm và trả độ lệch múi giờ tại đó, để ngày đổi giờ mùa hè vẫn đúng giờ.
     */
    fun nextFireMillis(
        nowEpoch: Long,
        hour: Int,
        minute: Int,
        offsetAt: (Long) -> Long = ::platformUtcOffsetMillis
    ): Long {
        val today = StudyDay.localClockTime(nowEpoch, hour, minute, offsetAt(nowEpoch))
        if (today > nowEpoch) return today
        val tomorrowProbe = nowEpoch + DAY_MS
        return StudyDay.localClockTime(tomorrowProbe, hour, minute, offsetAt(tomorrowProbe))
    }

    /** Nội dung thông báo: "12 từ cần ôn" khi biết số thẻ đến hạn, lời nhắc chung khi chưa có thẻ nào đến hạn. */
    fun message(dueCount: Int): String =
        if (dueCount > 0) "$dueCount từ cần ôn" else "Đã đến giờ ôn từ vựng hôm nay"

    const val TITLE = "Ôn từ vựng"
}

/**
 * Đặt (hoặc huỷ) lịch nhắc ôn hằng ngày theo [config].
 *
 * Android: WorkManager chạy định kỳ 24 giờ, tự đếm thẻ đến hạn lúc nổ. iOS: thông báo lịch lặp
 * (UNCalendarNotificationTrigger) với nội dung chụp theo [dueCount] tại thời điểm đặt — nên ứng dụng gọi lại
 * hàm này khi mở và sau mỗi phiên ôn để con số không cũ.
 *
 * @param replaceExisting true khi người dùng vừa đổi giờ (huỷ lịch cũ); false khi chỉ đảm bảo lịch tồn tại.
 */
expect fun scheduleReviewReminder(config: ReviewReminderConfig, dueCount: Int, replaceExisting: Boolean)
