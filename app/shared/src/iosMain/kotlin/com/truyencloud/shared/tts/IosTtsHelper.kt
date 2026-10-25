@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.truyencloud.shared.tts

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSRange
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.NSObject

/**
 * Nhận sự kiện của AVSpeechSynthesizer. Mỗi bộ tổng hợp (đọc truyện, đọc từ, nghe thử)
 * có delegate RIÊNG: trước đây đọc từ dùng chung bộ tổng hợp với đọc truyện nên khi đọc
 * xong một từ lại bắn "đã đọc xong đoạn" và truyện tự nhảy sang đoạn kế.
 */
internal class IosSynthesizerDelegate : NSObject(), AVSpeechSynthesizerDelegateProtocol {
    var onUtteranceStarted: ((AVSpeechUtterance) -> Unit)? = null
    var onUtteranceFinished: ((AVSpeechUtterance) -> Unit)? = null

    /** Sắp đọc từ bắt đầu ở vị trí (đơn vị UTF-16, như NSRange) trong chữ của câu đọc. */
    var onWillSpeakRange: ((AVSpeechUtterance, Int) -> Unit)? = null

    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didStartSpeechUtterance: AVSpeechUtterance) {
        onUtteranceStarted?.invoke(didStartSpeechUtterance)
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
        onUtteranceFinished?.invoke(didFinishSpeechUtterance)
    }

    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        willSpeakRangeOfSpeechString: CValue<NSRange>,
        utterance: AVSpeechUtterance
    ) {
        val callback = onWillSpeakRange ?: return
        callback(utterance, willSpeakRangeOfSpeechString.useContents { location.toInt() })
    }
}

internal class IosBackgroundTaskManager {
    private var transitionBgTaskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    fun beginTransitionBackgroundTask() {
        endTransitionBackgroundTask()
        try {
            transitionBgTaskId = UIApplication.sharedApplication.beginBackgroundTaskWithExpirationHandler {
                endTransitionBackgroundTask()
            }
        } catch (_: Exception) {}
    }

    fun endTransitionBackgroundTask() {
        try {
            if (transitionBgTaskId != UIBackgroundTaskInvalid) {
                val taskId = transitionBgTaskId
                transitionBgTaskId = UIBackgroundTaskInvalid
                UIApplication.sharedApplication.endBackgroundTask(taskId)
            }
        } catch (_: Exception) {}
    }
}
