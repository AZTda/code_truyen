package com.truyencloud.compose.platform

import androidx.compose.runtime.Composable

/**
 * Kết quả một lượt nhận dạng giọng nói (tiếng Anh, en-US).
 *
 * Chấm phát âm phải dựa vào những gì bộ nhận dạng THẬT SỰ nghe được. Bản cũ giả lập bằng cách gửi chính
 * từ đích lên máy chủ như thể người học đã đọc đúng, nên ai cũng được điểm tuyệt đối.
 */
sealed interface SpeechResult {
    /** Bản chép lời. */
    data class Transcript(val text: String) : SpeechResult

    /** Micro đã mở nhưng không nghe được tiếng nói nào. */
    data object NoSpeech : SpeechResult

    /** Người dùng từ chối quyền micro (hoặc quyền nhận dạng giọng nói trên iOS). */
    data object PermissionDenied : SpeechResult

    /** Thiết bị không có dịch vụ nhận dạng giọng nói — [reason] là câu giải thích cho người dùng. */
    data class Unavailable(val reason: String) : SpeechResult

    data class Failed(val message: String) : SpeechResult
}

interface SpeechRecognizerHandle {
    /** Đang mở micro nghe. Là state của Compose nên đọc trong composable sẽ tự vẽ lại. */
    val isListening: Boolean

    /** Bắt đầu nghe (tự xin quyền nếu cần). Kết quả trả qua callback của [rememberSpeechRecognizer]. */
    fun start()

    /** Ngừng nghe và nhận kết quả đã nghe được. */
    fun stop()

    /** Huỷ, KHÔNG trả kết quả. */
    fun cancel()
}

/**
 * Bộ nhận dạng giọng nói gắn với vòng đời composable (tự giải phóng khi rời khỏi màn hình).
 *
 * Android: android.speech.SpeechRecognizer, en-US, không trả kết quả tạm; quyền RECORD_AUDIO được xin lúc chạy
 * qua rememberLauncherForActivityResult. iOS: SFSpeechRecognizer + AVAudioEngine, xin quyền nhận dạng + micro.
 */
@Composable
expect fun rememberSpeechRecognizer(onResult: (SpeechResult) -> Unit): SpeechRecognizerHandle
