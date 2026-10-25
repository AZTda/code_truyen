@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.truyencloud.shared.tts

import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue

/**
 * Quản lý AVAudioSession của phiên đọc.
 *
 * - CHỈ kích hoạt khi bắt đầu phát ([acquire]) và nhả bằng `notifyOthersOnDeactivation` khi
 *   không còn ai dùng ([release]). Trước đây kích hoạt ngay khi khởi tạo nên vừa mở ứng dụng
 *   là nhạc của người dùng bị cắt.
 * - Nhiều bên (đọc truyện, nghe thử) có thể cùng giữ phiên; bên cuối cùng nhả mới tắt.
 * - Theo dõi ngắt quãng (cuộc gọi, Siri, báo thức) và rút tai nghe.
 */
internal class IosAudioSessionController(
    private val onInterruptionBegan: () -> Unit,
    private val onInterruptionEnded: (shouldResume: Boolean) -> Unit,
    private val onOutputDeviceLost: () -> Unit
) {
    private val owners = mutableSetOf<String>()
    private val observers = mutableListOf<Any>()
    private var active = false

    fun acquire(owner: String): Boolean {
        owners += owner
        return activate()
    }

    /** Kích hoạt lại sau ngắt quãng: hệ thống đã tự tắt phiên âm thanh của ta. */
    fun reactivate(): Boolean {
        active = false
        return activate()
    }

    fun release(owner: String) {
        owners -= owner
        if (owners.isNotEmpty() || !active) return
        active = false
        unregisterObservers()
        try {
            AVAudioSession.sharedInstance().setActive(
                false,
                withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                error = null
            )
        } catch (_: Exception) {}
    }

    private fun activate(): Boolean {
        // Luôn đặt lại category (rẻ): tính năng khác như luyện phát âm có thể đã đổi phiên sang
        // PlayAndRecord, khi đó tiếng đọc ra loa thoại rất nhỏ.
        val ok = try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(
                AVAudioSessionCategoryPlayback,
                mode = AVAudioSessionModeSpokenAudio,
                options = 0uL,
                error = null
            ) && session.setActive(true, error = null)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            active = true
            registerObservers()
        }
        return ok
    }

    private fun registerObservers() {
        if (observers.isNotEmpty()) return
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { n -> handleInterruption(n) }.let { observers += it }
        center.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { n -> handleRouteChange(n) }.let { observers += it }
    }

    private fun unregisterObservers() {
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers.clear()
    }

    private fun handleInterruption(n: NSNotification?) {
        if (owners.isEmpty()) return
        val info = n?.userInfo ?: return
        val type = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedIntegerValue ?: return
        when (type) {
            AVAudioSessionInterruptionTypeBegan -> onInterruptionBegan()
            AVAudioSessionInterruptionTypeEnded -> {
                val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedIntegerValue ?: 0uL
                onInterruptionEnded((options and AVAudioSessionInterruptionOptionShouldResume) != 0uL)
            }
        }
    }

    // Rút tai nghe / ngắt Bluetooth: dừng, không được tự phát ra loa ngoài
    private fun handleRouteChange(n: NSNotification?) {
        if (owners.isEmpty()) return
        val info = n?.userInfo ?: return
        val reason = (info[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.unsignedIntegerValue ?: return
        if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) onOutputDeviceLost()
    }
}
