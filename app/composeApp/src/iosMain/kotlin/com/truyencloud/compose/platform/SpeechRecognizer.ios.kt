package com.truyencloud.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSDate
import platform.Foundation.NSLocale
import platform.Foundation.NSTimer
import platform.Foundation.timeIntervalSince1970
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberSpeechRecognizer(onResult: (SpeechResult) -> Unit): SpeechRecognizerHandle {
    val latest = rememberUpdatedState(onResult)
    val handle = remember { IosSpeechHandle { latest.value(it) } }
    DisposableEffect(handle) { onDispose { handle.cancel() } }
    return handle
}

private fun onMain(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue()) { block() }
}

private fun nowSeconds(): Double = NSDate().timeIntervalSince1970

/**
 * SFSpeechRecognizer + AVAudioEngine (en-US).
 *
 * SFSpeechAudioBufferRecognitionRequest không tự kết thúc khi người nói im lặng, nên một bộ hẹn giờ theo dõi:
 * dừng khi bản chép không đổi ~1,4 giây sau khi đã có chữ, hoặc quá [MAX_SECONDS] giây.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosSpeechHandle(private val deliver: (SpeechResult) -> Unit) : SpeechRecognizerHandle {

    override var isListening by mutableStateOf(false)
        private set

    private val recognizer = SFSpeechRecognizer(locale = NSLocale(localeIdentifier = "en-US"))
    private val engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var timer: NSTimer? = null

    private var best = ""
    private var startedAt = 0.0
    private var lastChangeAt = 0.0
    private var stopRequestedAt = 0.0
    private var finished = true
    private var tapInstalled = false

    override fun start() {
        if (isListening) return
        if (!recognizer.available) {
            deliver(SpeechResult.Unavailable("Thiết bị chưa sẵn sàng nhận dạng giọng nói tiếng Anh. Hãy kiểm tra mạng hoặc Cài đặt > Siri & Tìm kiếm."))
            return
        }
        SFSpeechRecognizer.requestAuthorization { status ->
            onMain {
                if (status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                    requestMicrophone()
                } else {
                    deliver(SpeechResult.PermissionDenied)
                }
            }
        }
    }

    private fun requestMicrophone() {
        AVAudioSession.sharedInstance().requestRecordPermission { granted ->
            onMain { if (granted) begin() else deliver(SpeechResult.PermissionDenied) }
        }
    }

    private fun begin() {
        if (isListening) return
        finished = false
        best = ""
        stopRequestedAt = 0.0
        startedAt = nowSeconds()
        lastChangeAt = startedAt

        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, error = null)
        session.setActive(true, error = null)

        val req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        request = req

        val input = engine.inputNode
        if (tapInstalled) {
            input.removeTapOnBus(0u)
            tapInstalled = false
        }
        val format = input.outputFormatForBus(0u)
        input.installTapOnBus(0u, bufferSize = 1024u, format = format) { buffer, _ ->
            if (buffer != null) req.appendAudioPCMBuffer(buffer)
        }
        tapInstalled = true
        engine.prepare()
        if (!engine.startAndReturnError(null)) {
            teardown()
            deliver(SpeechResult.Failed("Không mở được micro."))
            return
        }
        isListening = true

        task = recognizer.recognitionTaskWithRequest(req) { result, error ->
            onMain {
                if (finished) return@onMain
                val text = result?.bestTranscription?.formattedString.orEmpty()
                if (text.isNotBlank() && text != best) {
                    best = text
                    lastChangeAt = nowSeconds()
                }
                if (result?.final == true) {
                    complete(null)
                } else if (error != null && result == null) {
                    complete(error.localizedDescription)
                }
            }
        }
        timer = NSTimer.scheduledTimerWithTimeInterval(0.4, true) { _ -> onTick() }
    }

    private fun onTick() {
        if (finished) return
        val now = nowSeconds()
        if (stopRequestedAt > 0.0) {
            // Đã báo hết tiếng mà kết quả cuối không tới: chốt bằng bản chép đang có.
            if (now - stopRequestedAt > 2.0) complete(null)
            return
        }
        val silentAfterSpeech = best.isNotBlank() && now - lastChangeAt > 1.4
        val tooLong = now - startedAt > MAX_SECONDS
        val neverSpoke = best.isBlank() && now - startedAt > NO_SPEECH_SECONDS
        if (silentAfterSpeech || tooLong || neverSpoke) stop()
    }

    override fun stop() {
        if (!isListening || stopRequestedAt > 0.0) return
        stopRequestedAt = nowSeconds()
        engine.stop()
        if (tapInstalled) {
            engine.inputNode.removeTapOnBus(0u)
            tapInstalled = false
        }
        request?.endAudio()
    }

    override fun cancel() {
        if (finished) return
        finished = true
        teardown()
    }

    private fun complete(errorText: String?) {
        if (finished) return
        finished = true
        val text = best
        teardown()
        deliver(
            when {
                text.isNotBlank() -> SpeechResult.Transcript(text)
                errorText != null && !errorText.contains("No speech", ignoreCase = true) ->
                    SpeechResult.Failed("Không nhận dạng được giọng nói: $errorText")
                else -> SpeechResult.NoSpeech
            }
        )
    }

    private fun teardown() {
        timer?.invalidate()
        timer = null
        if (engine.running) {
            engine.stop()
        }
        if (tapInstalled) {
            engine.inputNode.removeTapOnBus(0u)
            tapInstalled = false
        }
        task?.cancel()
        task = null
        request = null
        isListening = false
        // Trả phiên âm thanh về chế độ phát để giọng đọc (TTS) hoạt động như trước.
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
    }

    private companion object {
        const val MAX_SECONDS = 9.0
        const val NO_SPEECH_SECONDS = 6.0
    }
}
