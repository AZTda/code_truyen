package com.truyencloud.shared.notify

import platform.Foundation.NSDateComponents
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

private const val REMINDER_ID = "truyencloud_review_reminder"

/**
 * iOS: thông báo cục bộ lặp hằng ngày theo lịch (UNCalendarNotificationTrigger).
 *
 * iOS không cho chạy mã nền tuỳ ý lúc nổ nên nội dung ("12 từ cần ôn") được chụp tại thời điểm đặt lịch.
 * Vì vậy hàm này được gọi lại mỗi lần mở ứng dụng, khi ra nền và sau mỗi phiên ôn để con số không cũ quá lâu — kể cả với
 * khách chưa đăng nhập (xem VocabularyAutoSync: phần đồng bộ mạng bỏ qua, phần làm mới nhắc ôn vẫn chạy).
 * Quyền hiển thị thông báo được xin khi người dùng bật nhắc nhở (Notifier.requestPermission).
 */
actual fun scheduleReviewReminder(config: ReviewReminderConfig, dueCount: Int, replaceExisting: Boolean) {
    val center = UNUserNotificationCenter.currentNotificationCenter()
    // Cùng một identifier: đặt lại là THAY lịch cũ, không chồng thêm.
    center.removePendingNotificationRequestsWithIdentifiers(listOf(REMINDER_ID))
    if (!config.enabled) return

    val content = UNMutableNotificationContent()
    content.setTitle(ReviewReminderMath.TITLE)
    content.setBody(ReviewReminderMath.message(dueCount))
    content.setSound(UNNotificationSound.defaultSound())

    val time = NSDateComponents()
    time.setHour(config.hour.toLong())
    time.setMinute(config.minute.toLong())
    val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(time, repeats = true)

    val request = UNNotificationRequest.requestWithIdentifier(REMINDER_ID, content, trigger)
    center.addNotificationRequest(request, null)
}
