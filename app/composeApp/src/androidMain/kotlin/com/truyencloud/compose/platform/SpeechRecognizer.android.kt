package com.truyencloud.compose.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberSpeechRecognizer(onResult: (SpeechResult) -> Unit): SpeechRecognizerHandle {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onResult)
    val handle = remember(context) { AndroidSpeechHandle(context) { latest.value(it) } }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        handle.onPermissionResult(granted)
    }
    SideEffect { handle.requestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) } }
    DisposableEffect(handle) { onDispose { handle.destroy() } }
    return handle
}

private class AndroidSpeechHandle(
    private val context: Context,
    private val deliver: (SpeechResult) -> Unit
) : SpeechRecognizerHandle {

    override var isListening by mutableStateOf(false)
        private set

    /** Do composable gán (launcher xin quyền chỉ có trong composition). */
    var requestPermission: () -> Unit = {}

    private var recognizer: SpeechRecognizer? = null
    private var cancelled = false

    override fun start() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            deliver(
                SpeechResult.Unavailable(
                    "Thiết bị chưa có dịch vụ nhận dạng giọng nói (thường là ứng dụng Google). " +
                        "Hãy cài hoặc bật dịch vụ này rồi thử lại."
                )
            )
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
            return
        }
        beginListening()
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) beginListening() else deliver(SpeechResult.PermissionDenied)
    }

    private fun beginListening() {
        val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        cancelled = false
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            engine.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            deliver(SpeechResult.Failed(e.message ?: "Không mở được micro."))
        }
    }

    override fun stop() {
        if (isListening) recognizer?.stopListening()
    }

    override fun cancel() {
        cancelled = true
        isListening = false
        recognizer?.cancel()
    }

    fun destroy() {
        cancelled = true
        isListening = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun finish(result: SpeechResult) {
        isListening = false
        if (!cancelled) deliver(result)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
            finish(if (text.isNullOrBlank()) SpeechResult.NoSpeech else SpeechResult.Transcript(text))
        }

        override fun onError(error: Int) {
            finish(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechResult.NoSpeech
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechResult.PermissionDenied
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER ->
                        SpeechResult.Failed("Dịch vụ nhận dạng giọng nói cần mạng mà hiện chưa kết nối được.")
                    SpeechRecognizer.ERROR_AUDIO -> SpeechResult.Failed("Không thu được âm thanh từ micro.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        SpeechResult.Failed("Bộ nhận dạng giọng nói đang bận, hãy thử lại sau vài giây.")
                    ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
                        SpeechResult.Unavailable("Thiết bị chưa hỗ trợ nhận dạng tiếng Anh (en-US). Hãy tải gói ngôn ngữ trong cài đặt giọng nói.")
                    else -> SpeechResult.Failed("Không nhận dạng được giọng nói (mã lỗi $error).")
                }
            )
        }
    }

    private companion object {
        // Hằng số API 31+ — ghi số trực tiếp để không phụ thuộc phiên bản SDK lúc biên dịch.
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
