package com.truyencloud.shared.notify

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSUUID
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * Thông báo trên iOS qua UNUserNotificationCenter.
 *
 * Khác Android: iOS không cho chạy nền theo chu kỳ tuỳ ý, nên việc kiểm tra chương
 * mới diễn ra lúc ứng dụng được mở lại (xem MainViewController). Đây là thông báo
 * cục bộ, không phải đẩy từ máy chủ.
 */
class IosNotifier : Notifier {

    // Trạng thái quyền đọc được bất đồng bộ; giữ lại bản đọc gần nhất để
    // `hasPermission` trả lời ngay mà không chặn luồng.
    private var cachedAuthorized: Boolean = false

    init {
        refreshAuthorization()
    }

    override val hasPermission: Boolean
        get() = cachedAuthorized

    private fun refreshAuthorization() {
        UNUserNotificationCenter.currentNotificationCenter()
            .getNotificationSettingsWithCompletionHandler { settings ->
                val status = settings?.authorizationStatus
                cachedAuthorized = status == UNAuthorizationStatusAuthorized ||
                    status == UNAuthorizationStatusProvisional
            }
    }

    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        ) { granted, _ ->
            cachedAuthorized = granted
            if (cont.isActive) cont.resume(granted)
        }
    }

    override fun notifyNewChapters(notices: List<NewChapterNotice>) {
        if (notices.isEmpty()) return

        val content = UNMutableNotificationContent()
        if (notices.size == 1) {
            val n = notices.first()
            content.setTitle(n.novelTitle)
            content.setBody("Có ${n.newChapters} chương mới (tổng ${n.totalChapters}).")
        } else {
            val total = notices.sumOf { it.newChapters }
            content.setTitle("$total chương mới trong tủ sách")
            content.setBody(notices.joinToString(", ") { "${it.novelTitle} (+${it.newChapters})" })
        }
        content.setSound(UNNotificationSound.defaultSound())

        // Trigger nil sẽ bắn NGAY, và iOS bỏ qua thông báo bắn ngay khi ứng dụng
        // đang hiện trên màn hình. Hoãn một nhịp ngắn để nó vẫn hiện ra.
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = 1.0,
            repeats = false
        )
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = NSUUID().UUIDString(),
            content = content,
            trigger = trigger
        )
        UNUserNotificationCenter.currentNotificationCenter()
            .addNotificationRequest(request, null)
    }
}

actual fun createNotifier(): Notifier = IosNotifier()

/**
 * iOS khong co tuong duong WorkManager de chay theo chu ky tuy y, nen o day khong
 * dat lich gi ca. Viec kiem tra chay luc ung dung duoc mo lai (MainViewController).
 */
actual fun applyBackgroundUpdateChecks(enabled: Boolean) {
    // Khong lam gi — xem ghi chu tren.
}

actual fun backgroundUpdateCheckDescription(): String =
    "Kiểm tra mỗi lần bạn mở lại ứng dụng."
