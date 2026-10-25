package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.tts.KtorTtsPrerenderApi
import com.truyencloud.shared.tts.TtsPrerenderApi
import com.truyencloud.shared.tts.TtsPrerenderRequest
import com.truyencloud.shared.tts.TtsReadiness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Chương / giọng / bản chữ cần máy chủ dựng sẵn giọng AI. [segmentCount] để nhận ra nội dung chương vừa đổi. */
internal data class TtsPrerenderTarget(
    val novelId: String,
    val chapterNumber: Int,
    val voiceId: String,
    val lang: String?,
    val segmentCount: Int
)

/**
 * Bản chữ nào máy chủ có để dựng sẵn: bản Việt dùng chung ("vi"), bản Anh ("en"), bản gốc ("original"). Bản hoà trộn và
 * song ngữ là chữ riêng của app (máy chủ không có đúng chữ đó) nên không dựng sẵn — chúng đi đường dựng tại chỗ.
 * Chỉ khi màn đọc đang mở, đã có chữ và đang chọn giọng AI.
 */
internal fun ReaderUiState.prerenderTarget(): TtsPrerenderTarget? {
    if (!isReaderVisible || !ttsOnlineMode || isLoadingChapter || paragraphs.isEmpty()) return null
    val novelId = novel?.id ?: return null
    val chapter = currentChapter ?: return null
    val lang = when {
        isProgressiveMode && progressiveParagraphs.isNotEmpty() -> return null
        isBilingualMode && englishParagraphs.isNotEmpty() -> return null
        isEnglishMode && englishParagraphs.isNotEmpty() -> "en"
        showOriginal -> "original"
        else -> "vi"
    }
    return TtsPrerenderTarget(novelId, chapter.chapterNumber, ttsVoiceProfileId, lang, ttsSegments.size)
}

/**
 * Nhờ máy chủ dựng sẵn giọng AI TRƯỚC khi người dùng bấm nghe: chương đang mở (ưu tiên 1) và hai chương kế (ưu tiên 2) bằng
 * giọng đang chọn, một lời gọi `count = 3`. Chờ [DEBOUNCE_MS] cho yên (lật chương liên tục không gọi liên tục); đổi chương /
 * đổi giọng / rời màn đọc thì lượt đang chờ bị huỷ ([collectLatest]). Lỗi im lặng: không bao giờ chặn việc đọc.
 *
 * Tiến độ của chương đang mở ([readiness]) là thứ thanh nghe đọc dùng để "chờ rồi tự phát".
 */
internal class ReaderTtsPrerender(
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val fromSegment: (TtsPrerenderTarget) -> Int,
    private val api: TtsPrerenderApi = KtorTtsPrerenderApi()
) {
    private val _readiness = MutableStateFlow<TtsReadiness?>(null)
    val readiness: StateFlow<TtsReadiness?> = _readiness.asStateFlow()

    fun start() {
        scope.launch {
            uiStateFlow.map { it.prerenderTarget() }.distinctUntilChanged().collectLatest { target ->
                if (target == null) return@collectLatest
                delay(DEBOUNCE_MS)
                request(target, fromSegment(target), count = AHEAD_CHAPTERS)
            }
        }
    }

    /** Hỏi (và xếp dựng) chương [target] từ mảnh [from]; gọi lại chính là hỏi tiến độ. null = lỗi / máy chủ đời trước. */
    suspend fun request(target: TtsPrerenderTarget, from: Int, count: Int = 1): TtsReadiness? {
        val response = api.prerender(
            TtsPrerenderRequest(
                novel_id = target.novelId,
                chapter_number = target.chapterNumber,
                count = count,
                voice = target.voiceId,
                lang = target.lang,
                priority = 1,
                from_segment = from.coerceAtLeast(0)
            )
        ) ?: return null
        val chapter = response.chapters.firstOrNull { it.chapter_number == target.chapterNumber } ?: return null
        val readiness = TtsReadiness.from(target.novelId, target.voiceId, chapter)
        _readiness.value = readiness
        return readiness
    }

    private companion object {
        const val DEBOUNCE_MS = 1_500L
        const val AHEAD_CHAPTERS = 3
    }
}
