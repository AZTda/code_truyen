package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.repo.ReaderRepository
import com.truyencloud.shared.repo.UserRepository
import com.truyencloud.shared.text.ParagraphSplitter
import com.truyencloud.shared.tts.TtsPlaybackPosition
import com.truyencloud.shared.tts.TtsSourceSync
import com.truyencloud.shared.tts.TtsSpeaker
import com.truyencloud.shared.tts.TtsStartPolicy
import com.truyencloud.shared.tts.TtsTextLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Điều phối phiên nghe của màn hình đọc: giữ `ttsSegments` / đoạn đang nghe của giao diện khớp với văn bản mà bộ đọc đang giữ.
 *
 * Chuyển chương: đọc hết chương -> [pendingAdvance]; chương kế tải xong thì tự phát NẾU người dùng không tạm dừng trong lúc
 * chờ — nếu có thì chỉ nạp văn bản bằng `prepare` (bấm Phát trong lúc chờ = [playRequested]). Bản đọc đổi giữa chừng (bật / tắt
 * chế độ Anh, bản dịch mới...) so với [sessionSource] thì bộ đọc được nạp lại ở đoạn tương ứng.
 */
class ReaderTtsCoordinator(
    private val ttsSpeaker: TtsSpeaker,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val userRepository: UserRepository? = null,
    readerRepository: ReaderRepository? = null,
    private val onChapterCompleted: () -> Unit
) {
    internal val settings = ReaderTtsSettingsActions(ttsSpeaker, uiStateFlow, userRepository)
    private val progressSaver = ReaderTtsProgressSaver(readerRepository, uiStateFlow, scope)
    private val mirror = ReaderTtsStateMirror(ttsSpeaker, uiStateFlow, scope, userRepository)
    private val resume = ReaderTtsResume(ttsSpeaker, uiStateFlow, scope)
    private val prerender = ReaderTtsPrerender(uiStateFlow, scope, fromSegment = ::prerenderFrom)
    private val gate = ReaderTtsStartGate(uiStateFlow, scope, prerender)

    /** Chương mà `ttsSegments` đang mô tả; đổi chương thì đoạn đang nghe phải về 0. */
    private var segmentsChapterId: String? = null

    /** Chương mà phiên đọc hiện tại thuộc về. */
    private var sessionChapterId: String? = null

    /** Văn bản (theo đoạn hiển thị) mà bộ đọc đang giữ; bản đọc đổi giữa chừng thì phải nạp lại cho khớp bản đồ đoạn. */
    private var sessionSource: List<String>? = null

    /** Đã đọc xong chương, đang chờ tải chương kế: chương kế phải bắt đầu từ đầu. */
    private var pendingAdvance = false

    /** Người dùng bấm Phát trong lúc chờ chương kế (trước đó đã tạm dừng): chương tải xong thì đọc ngay. */
    private var playRequested = false

    private var lastScrollFraction = -1f
    private var errorJob: Job? = null

    private val completionListener: () -> Unit = { handleChapterCompleted() }
    private val isPlayingOrWaiting: Boolean get() = ttsSpeaker.isPlaying.value || gate.isWaiting

    private var state: ReaderUiState
        get() = uiStateFlow.value
        set(value) { uiStateFlow.value = value }

    fun setup() {
        scope.launch {
            ttsSpeaker.isPlaying.collect { playing ->
                state = state.copy(ttsIsPlaying = playing, ttsError = if (playing) null else state.ttsError)
                if (!playing) { progressSaver.flush(); resume.flush() }
            }
        }
        scope.launch { ttsSpeaker.currentParagraphIndex.collect(::onSegmentChanged) }
        mirror.start()
        resume.start()
        prerender.start()
        scope.launch { prerender.readiness.collect { state = state.copy(ttsReadiness = it) } }
        // drop(1): bỏ lỗi cũ còn sót lại của phiên trước (StateFlow phát lại giá trị hiện tại khi đăng ký)
        scope.launch { ttsSpeaker.lastError.drop(1).collect { if (it != null) showError(it) } }
        // Tải chương kế hỏng khi đang tự chuyển chương: dừng hẳn để nhả wake lock + thông báo
        scope.launch {
            uiStateFlow.map { it.chapterError }.distinctUntilChanged().collect { error ->
                if (error != null && pendingAdvance && state.isTtsActive) onAdvanceFailed(state.ttsError ?: ADVANCE_FAILED_MESSAGE)
            }
        }
        // Bản đọc đổi giữa chừng (bật / tắt chế độ Anh, bản Anh vừa về, bản dịch được thay...): bộ đọc phải đọc cùng bản với bản đồ đoạn
        scope.launch {
            uiStateFlow.map { it.ttsSource() }.distinctUntilChanged { old, new -> old === new }.collect { syncSessionWithSource() }
        }
    }

    private fun onSegmentChanged(segmentIdx: Int) {
        val st = state
        // Phiên đã đóng: bộ đọc đặt lại chỉ số về 0 không được ghi đè vị trí đang nghe
        if (!st.isTtsActive) return
        val paragraph = if (st.ttsSegments.isEmpty()) segmentIdx
        else ParagraphSplitter.paragraphIndexForSegment(st.ttsSegments, segmentIdx)
        if (paragraph != st.ttsCurrentParagraph) state = st.copy(ttsCurrentParagraph = paragraph)
        if (sessionChapterId != null && sessionChapterId == st.currentChapter?.id) {
            progressSaver.onParagraphChanged(paragraph)
        }
    }

    fun rebuildTtsSegments(): List<String> {
        val st = state
        val segments = ParagraphSplitter.buildTtsSegments(st.ttsSource())
        val chapterId = st.currentChapter?.id
        val chapterChanged = chapterId != segmentsChapterId
        segmentsChapterId = chapterId
        if (chapterChanged) lastScrollFraction = -1f
        // Đổi chương: đoạn đang nghe của chương cũ không được rơi sang chương mới (lỗi "đọc từ giữa chương")
        state = st.copy(
            ttsSegments = segments,
            ttsCurrentParagraph = if (chapterChanged) 0 else st.ttsCurrentParagraph
        )
        ttsSpeaker.setSegmentParagraphMap(segments.map { it.paragraphIndex }, st.ttsSource().size)
        return ParagraphSplitter.ttsTexts(segments)
    }

    /** Bấm nghe: chương có chỗ nghe dở đã lưu thì đọc tiếp ĐÚNG chữ đó; không thì đoạn đang nhìn thấy / đang dở của phiên. */
    fun startTts() {
        val st = state
        val sameSession = st.isTtsActive && sessionChapterId != null && sessionChapterId == st.currentChapter?.id
        if (!sameSession) savedStart(st)?.let { return beginSession(0, resumeAt = it) }
        beginSession(
            TtsStartPolicy.manualStart(
                sameSession = sameSession,
                sessionParagraph = st.ttsCurrentParagraph,
                restoreScroll = st.restoreScrollPosition,
                visibleScroll = lastScrollFraction,
                paragraphCount = st.ttsSource().size
            )
        )
    }

    /** Gọi sau khi một chương vừa tải xong mà TTS đang bật (tự chuyển chương, chọn từ mục lục, mở từ chi tiết truyện). */
    fun startTtsAfterChapterLoad() {
        val st = state
        val advancing = pendingAdvance
        // Người dùng đã tạm dừng (hoặc hẹn giờ ngủ đã dừng) trong lúc chờ chương kế: nạp chương kế nhưng KHÔNG tự phát lại
        val autoPlay = TtsStartPolicy.autoPlayAfterLoad(advancing, isPlayingOrWaiting, playRequested)
        pendingAdvance = false
        playRequested = false
        // Người dùng đã đóng TTS trong lúc chương đang tải: không tự bật lại
        if (!st.isTtsActive) {
            rebuildTtsSegments()
            return
        }
        // Chưa có bản dịch: trình đọc rơi về bản gốc tiếng Trung, không tự đọc thành tiếng
        if (!st.showOriginal && TtsTextLanguage.isMostlyChinese(st.ttsSource())) {
            rebuildTtsSegments()
            pauseForUntranslated()
            return
        }
        // Tự sang chương kế luôn đọc từ đầu; mở chương (mục lục, chi tiết truyện) thì nghe tiếp chỗ đã dừng nếu có
        val saved = if (advancing) null else savedStart(st)
        beginSession(TtsStartPolicy.afterChapterLoad(advancing, st.restoreScrollPosition, st.ttsSource().size), autoPlay, saved)
    }

    private fun savedStart(st: ReaderUiState): TtsPlaybackPosition? =
        resume.savedStart(st.novel?.id, st.currentChapter?.chapterNumber ?: 0, ParagraphSplitter.ttsTexts(st.ttsSegments))

    /** Máy chủ dựng trước từ mảnh nào: mảnh đang nghe của phiên này, chỗ nghe dở đã lưu, hoặc đầu chương. */
    private fun prerenderFrom(target: TtsPrerenderTarget): Int {
        val st = state
        val chapter = st.currentChapter ?: return 0
        if (chapter.chapterNumber != target.chapterNumber) return 0
        if (st.isTtsActive && sessionChapterId == chapter.id) return ttsSpeaker.currentParagraphIndex.value
        return savedStart(st)?.segmentIndex ?: 0
    }

    /** Nội dung chương vừa đổi (vừa dịch xong): đọc lại từ đầu bản mới; đang tạm dừng thì chỉ nạp bản mới, không tự phát. */
    fun restartWithNewContent() {
        // Đang chờ chương kế thì nội dung vừa đổi là của chương CŨ: không kéo phiên quay lại đọc nó
        if (state.isTtsActive && !pendingAdvance) beginSession(0, isPlayingOrWaiting) else rebuildTtsSegments()
    }

    /**
     * Nguồn đọc đổi trong lúc đang nghe (bật / tắt chế độ tiếng Anh, bản Anh vừa về...) mà bộ đọc vẫn giữ văn bản cũ: nạp
     * lại đúng đoạn tương ứng của bản mới. Nếu không, bản đồ mảnh -> đoạn lệch: tô sáng nhảy về đầu chương và tiến độ
     * nghe bị ghi đè ở đầu chương.
     */
    private fun syncSessionWithSource() {
        val st = state
        val old = sessionSource ?: return
        val sameSession = st.isTtsActive && !pendingAdvance && sessionChapterId != null && sessionChapterId == st.currentChapter?.id
        if (!sameSession) return
        val paragraph = TtsSourceSync.paragraphToResume(old, st.ttsSource(), ttsSpeaker.currentParagraphIndex.value) ?: return
        resume.clearCurrent()
        beginSession(paragraph, isPlayingOrWaiting)
    }

    /**
     * [resumeAt]: nghe tiếp đúng chữ đã lưu (thay cho đầu đoạn [paragraph]). Bắt đầu phát giọng AI khi bộ đọc đang dừng thì
     * qua [gate]: nạp chữ trước, chờ máy chủ dựng đủ (nếu cần) rồi mới phát.
     */
    private fun beginSession(paragraph: Int, autoPlay: Boolean = true, resumeAt: TtsPlaybackPosition? = null) {
        val texts = rebuildTtsSegments()
        if (texts.isEmpty()) return
        val st = state
        ttsSpeaker.applyChapterMetadata(st)
        ttsSpeaker.setOnChapterCompleted(completionListener)
        sessionChapterId = st.currentChapter?.id
        sessionSource = st.ttsSource()
        pendingAdvance = false
        playRequested = false
        errorJob?.cancel()
        gate.cancel()
        val start = resumeAt ?: TtsPlaybackPosition.startOf(ParagraphSplitter.firstSegmentOfParagraph(st.ttsSegments, paragraph))
        val para = if (resumeAt != null) ParagraphSplitter.paragraphIndexForSegment(st.ttsSegments, start.segmentIndex) else paragraph
        state = st.copy(isTtsActive = true, ttsCurrentParagraph = para, ttsError = null)
        resume.bind(st.novel?.id, st.currentChapter?.chapterNumber ?: 0, texts)
        if (autoPlay && st.ttsOnlineMode && !ttsSpeaker.isPlaying.value) {
            ttsSpeaker.speakFrom(texts, start, autoPlay = false)
            gate.startWhenReady(texts.size, start.segmentIndex) { ttsSpeaker.resume() }
        } else {
            ttsSpeaker.speakFrom(texts, start, autoPlay)
        }
    }

    fun toggleTtsPlayPause() {
        val st = state
        when {
            // Đang chờ máy chủ dựng giọng: bấm lần nữa là thôi chờ (không phát)
            gate.isWaiting -> gate.cancel()
            st.ttsIsPlaying -> ttsSpeaker.pause()
            pendingAdvance -> resumeAdvance()
            // Đang dừng giữa chừng: tiếp tục ĐÚNG chỗ dừng, không dựng lại phiên từ đầu
            st.isTtsActive && sessionChapterId != null && sessionChapterId == st.currentChapter?.id &&
                st.ttsSegments.isNotEmpty() -> {
                ttsSpeaker.setOnChapterCompleted(completionListener)
                ttsSpeaker.resume()
            }
            else -> startTts()
        }
    }

    // Người dùng tự chọn chỗ khác: chỗ nghe dở đã lưu không còn là chỗ họ muốn quay lại
    fun nextTtsParagraph() { resume.clearCurrent(); ttsSpeaker.nextParagraph() }
    fun prevTtsParagraph() { resume.clearCurrent(); ttsSpeaker.prevParagraph() }
    fun jumpTtsParagraph(index: Int) { resume.clearCurrent(); beginSession(index) }

    /** Ứng dụng vào nền / rời màn đọc: ghi ngay chỗ đang nghe. */
    fun flushPosition() = resume.flush()

    fun closeTts() {
        gate.cancel()
        progressSaver.flush()
        resume.flush()
        // Đóng phiên TRƯỚC khi dừng bộ đọc: stop() phát lại chỉ số, bộ thu phải thấy phiên đã đóng
        state = state.copy(isTtsActive = false, ttsError = null)
        sessionChapterId = null
        sessionSource = null
        pendingAdvance = false
        playRequested = false
        errorJob?.cancel()
        ttsSpeaker.stop()
    }

    /** Vị trí cuộn mới nhất của màn hình đọc, dùng để biết người dùng đang nhìn đoạn nào khi bấm nghe. */
    fun noteScrollPosition(position: Float) {
        lastScrollFraction = position
    }

    private fun handleChapterCompleted() {
        val st = state
        if (!st.isTtsActive) return
        progressSaver.flush()
        // Nghe hết chương: lần mở lại chương này không "nghe tiếp" từ chữ cuối
        resume.clearCurrent()
        if (!st.ttsAutoNextChapter) {
            closeTts()
            return
        }
        if (st.isLastKnownChapter()) {
            ttsSpeaker.pause()
            showError("Bạn đã nghe hết chương cuối của truyện.")
            return
        }
        pendingAdvance = true
        playRequested = false
        onChapterCompleted()
    }

    /**
     * Bấm Phát khi đang chờ chương kế (đã tạm dừng trong lúc chờ, hoặc chương kế tải hỏng): ghi nhận "phát ngay khi tải
     * xong". Chương còn đang tải thì chỉ thế thôi — gọi lại việc sang chương sẽ tải lượt thứ hai và đếm chương đã đọc hai
     * lần; chỉ khi không còn gì đang tải (đã hỏng / bị huỷ) mới tải lại.
     */
    private fun resumeAdvance() {
        val st = state
        state = st.copy(ttsError = null)
        playRequested = true
        if (st.isLoadingChapter || st.isTranslating) return
        ttsSpeaker.setOnChapterCompleted(completionListener)
        onChapterCompleted()
    }

    private fun onAdvanceFailed(message: String) {
        playRequested = false
        gate.cancel()
        ttsSpeaker.pause()
        showError(message)
    }

    /**
     * Chương vừa tải xong nhưng không có gì để đọc (trống, chưa dịch, dịch hỏng...). Đang tự chuyển chương thì dừng ngay
     * kèm lý do — nếu không phiên cứ ở trạng thái "đang chuyển chương" (giữ wake lock, thông báo) tới khi quá 4 phút.
     */
    fun onChapterUnspeakable() {
        val st = state
        if (!pendingAdvance || !st.isTtsActive) return
        onAdvanceFailed(st.ttsError ?: st.translationError ?: EMPTY_CHAPTER_MESSAGE)
    }

    private fun pauseForUntranslated() {
        sessionChapterId = null
        ttsSpeaker.applyChapterMetadata(state)
        ttsSpeaker.pause()
        state = state.copy(isTtsActive = true, ttsIsPlaying = false)
        showError("Chương này chưa có bản dịch nên đã tạm dừng đọc. Hãy dịch chương rồi bấm phát.")
    }

    private fun showError(message: String) {
        state = state.copy(ttsError = message)
        errorJob?.cancel()
        errorJob = scope.launch {
            delay(ERROR_VISIBLE_MS)
            if (state.ttsError == message) state = state.copy(ttsError = null)
        }
    }

    /** Đổi giọng trong lúc đang chờ máy chủ dựng: chờ lại cho giọng mới. Các cài đặt khác: [settings]. */
    fun setTtsVoiceProfile(profileId: String) {
        settings.setVoiceProfile(profileId)
        gate.restart()
    }

    private companion object {
        const val ERROR_VISIBLE_MS = 12_000L
        const val ADVANCE_FAILED_MESSAGE = "Không tải được chương tiếp theo nên đã dừng đọc. Bấm phát để thử lại."
        const val EMPTY_CHAPTER_MESSAGE = "Chương tiếp theo chưa có nội dung để đọc nên đã dừng. Bấm phát để thử lại."
    }
}
