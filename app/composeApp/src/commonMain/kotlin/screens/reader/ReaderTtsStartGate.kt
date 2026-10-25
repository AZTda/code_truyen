package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.tts.TtsReadiness
import com.truyencloud.shared.tts.TtsStartGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * "Bấm Phát khi giọng AI của chương chưa dựng đủ": hiện dòng êm "Đang chuẩn bị giọng đọc… 35%" trên thanh nghe đọc, hỏi lại
 * máy chủ mỗi [TtsStartGate.POLL_MS] và TỰ PHÁT ngay khi đủ để nghe liền mạch. Đã đủ thì phát ngay. Không bao giờ âm thầm đổi
 * sang giọng khác vì phải chờ: hết cách chờ (máy chủ không trả lời, chờ quá lâu) thì vẫn phát bằng giọng AI, luồng phát tự
 * dựng tại chỗ hoặc báo lỗi rồi mới chuyển sang giọng của điện thoại (có thông báo).
 *
 * Người dùng bấm dừng / đóng / đổi chương trong lúc chờ thì [cancel]; đổi giọng thì chờ lại cho giọng mới ([restart]).
 */
internal class ReaderTtsStartGate(
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val prerender: ReaderTtsPrerender
) {
    private class Pending(val textsCount: Int, val fromSegment: Int, val play: () -> Unit)

    private var job: Job? = null
    private var pending: Pending? = null

    val isWaiting: Boolean get() = job?.isActive == true

    /**
     * Phát ([play]) khi được: ngay nếu đang không chọn giọng AI / máy chủ không dựng sẵn được chữ này / đã đủ; nếu chưa
     * biết hay chưa đủ thì hỏi máy chủ rồi chờ. Gọi lại thì lượt chờ trước bị thay.
     */
    fun startWhenReady(textsCount: Int, fromSegment: Int, play: () -> Unit) {
        cancel()
        val target = uiStateFlow.value.prerenderTarget()
        if (target == null) return play()
        val cached = prerender.readiness.value?.takeIf { it.isFor(target) }
        if (cached != null && !TtsStartGate.mustWait(true, cached, target.novelId, target.chapterNumber, target.voiceId, textsCount)) {
            return play()
        }
        val request = Pending(textsCount, fromSegment, play)
        pending = request
        showLabel(cached?.waitingLabel ?: PREPARING)
        job = scope.launch { waitThenPlay(target, request) }
    }

    /** Đổi giọng trong lúc chờ: chờ lại từ đầu cho giọng mới. */
    fun restart() {
        val request = pending ?: return
        if (isWaiting) startWhenReady(request.textsCount, request.fromSegment, request.play)
    }

    fun cancel() {
        job?.cancel()
        job = null
        pending = null
        showLabel(null)
    }

    private suspend fun waitThenPlay(target: TtsPrerenderTarget, request: Pending) {
        val started = TimeSource.Monotonic.markNow()
        var failures = 0
        var first = true
        while (true) {
            if (!first) delay(TtsStartGate.POLL_MS)
            val readiness = withTimeoutOrNull(POLL_TIMEOUT_MS) { prerender.request(target, request.fromSegment) }
            if (readiness == null) failures++ else failures = 0
            // Lần hỏi đầu không được trả lời (máy chủ đời trước / mất mạng): phát luôn, đừng bắt người dùng chờ
            if (first && readiness == null) break
            first = false
            readiness?.let { showLabel(it.waitingLabel) }
            if (!TtsStartGate.keepWaiting(readiness, failures, started.elapsedNow().inWholeMilliseconds, request.textsCount)) break
        }
        job = null
        pending = null
        showLabel(null)
        request.play()
    }

    private fun showLabel(label: String?) {
        val st = uiStateFlow.value
        if (st.ttsPreparingLabel != label) uiStateFlow.value = st.copy(ttsPreparingLabel = label)
    }

    private fun TtsReadiness.isFor(target: TtsPrerenderTarget) =
        novelId == target.novelId && chapterNumber == target.chapterNumber && voiceId == target.voiceId

    private companion object {
        const val PREPARING = "Đang chuẩn bị giọng đọc…"
        const val POLL_TIMEOUT_MS = 5_000L
    }
}
