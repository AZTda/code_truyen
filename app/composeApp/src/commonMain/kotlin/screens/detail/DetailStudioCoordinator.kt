package com.truyencloud.compose.screens.detail

import com.truyencloud.compose.screens.reader.GlossaryUiState
import com.truyencloud.compose.screens.reader.TtsVoiceChoices
import com.truyencloud.shared.api.GlossaryServerTerm
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.TermMemory
import com.truyencloud.shared.reader.AiErrors
import com.truyencloud.shared.reader.GlossaryEntry
import com.truyencloud.shared.reader.GlossaryMerge
import com.truyencloud.shared.reader.GlossaryTerms
import com.truyencloud.shared.repo.TermMemoryRepository
import com.truyencloud.shared.repo.UserRepository
import com.truyencloud.shared.repo.VocabularyRepository
import com.truyencloud.shared.tts.KtorTtsPrerenderApi
import com.truyencloud.shared.tts.TtsPrerenderApi
import com.truyencloud.shared.tts.TtsPrerenderRequest
import com.truyencloud.shared.tts.TtsSpeaker
import com.truyencloud.shared.tts.TtsVoiceCatalog
import com.truyencloud.shared.tts.TtsVoiceCatalogRepository
import com.truyencloud.shared.tts.TtsVoiceProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailStudioUiState(
    val glossary: GlossaryUiState = GlossaryUiState(),
    val glossaryCount: Int = 0,
    val voiceProfiles: List<TtsVoiceProfile> = TtsVoiceCatalog.voices,
    val ttsVoiceProfileId: String = TtsVoiceCatalog.defaultProfile().id,
    val ttsVoiceTone: String = TtsVoiceCatalog.defaultProfile().name,
    val showVoicePicker: Boolean = false,
    val showVocabSheet: Boolean = false,
    val novelWords: List<SavedWord> = emptyList(),
    val allWords: List<SavedWord> = emptyList()
) {
    val activeVoice: TtsVoiceProfile
        get() = TtsVoiceChoices.heard(ttsVoiceProfileId, ttsVoiceTone)
}

class DetailStudioCoordinator(
    private val termMemoryRepository: TermMemoryRepository? = null,
    private val vocabularyRepository: VocabularyRepository? = null,
    private val userRepository: UserRepository? = null,
    private val ttsSpeaker: TtsSpeaker? = null,
    private val scope: CoroutineScope,
    private val prerenderApi: TtsPrerenderApi = KtorTtsPrerenderApi(),
    private val onBatchFeedback: (isQueuing: Boolean, progress: Float?, message: String?) -> Unit = { _, _, _ -> }
) {
    private val _state = MutableStateFlow(DetailStudioUiState())
    val state: StateFlow<DetailStudioUiState> = _state.asStateFlow()

    private var currentNovelId: String = ""
    private var currentBookTitle: String = ""
    private var localTerms: List<TermMemory> = emptyList()
    private var serverTerms: List<GlossaryServerTerm> = emptyList()
    private var serverNotice: String? = null
    private var termObserveJob: Job? = null
    private var serverTermJob: Job? = null
    private var messageJob: Job? = null
    private var prerenderJob: Job? = null

    init {
        scope.launch {
            TtsVoiceCatalog.active.collect { snap ->
                _state.update { it.copy(voiceProfiles = snap.voices) }
            }
        }
        userRepository?.let { repo ->
            scope.launch {
                repo.profile.collect { p ->
                    _state.update {
                        it.copy(ttsVoiceProfileId = p.ttsVoiceProfileId, ttsVoiceTone = p.ttsVoiceTone)
                    }
                }
            }
        }
        vocabularyRepository?.let { repo ->
            scope.launch {
                try {
                    repo.getAllWords().collect { words ->
                        _state.update { s ->
                            s.copy(
                                allWords = words,
                                novelWords = filterNovelWords(words, currentNovelId, currentBookTitle)
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
        }
    }

    fun bindNovel(novelId: String, bookTitle: String) {
        if (novelId.isBlank()) return
        val changed = novelId != currentNovelId
        currentNovelId = novelId
        currentBookTitle = bookTitle
        TtsVoiceCatalogRepository.shared.refreshAsync(scope, TruyenCloudApi.baseUrl)
        _state.update { s ->
            s.copy(novelWords = filterNovelWords(s.allWords, novelId, bookTitle))
        }
        if (changed) {
            localTerms = emptyList()
            serverTerms = emptyList()
            serverNotice = null
            watchTerms(novelId)
        }
    }

    private fun filterNovelWords(all: List<SavedWord>, novelId: String, title: String): List<SavedWord> {
        val cleanTitle = title.trim().lowercase()
        return all.filter { w ->
            (novelId.isNotBlank() && w.novelId == novelId) ||
                (cleanTitle.isNotBlank() && w.bookTitle.trim().lowercase() == cleanTitle)
        }
    }

    private fun watchTerms(novelId: String) {
        val repo = termMemoryRepository ?: return
        termObserveJob?.cancel()
        termObserveJob = scope.launch {
            try {
                repo.observe(novelId).collect { terms ->
                    localTerms = terms
                    publishGlossary()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        serverTermJob?.cancel()
        serverTermJob = scope.launch {
            try {
                serverTerms = repo.serverTerms(novelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                serverNotice = "Chưa lấy được thuật ngữ tự động (${AiErrors.message(e)})."
            }
            publishGlossary(loading = false)
        }
    }

    fun openGlossary() {
        if (currentNovelId.isBlank()) return
        _state.update { it.copy(glossary = it.glossary.copy(visible = true, isLoading = termMemoryRepository != null && serverTerms.isEmpty())) }
        watchTerms(currentNovelId)
    }

    fun closeGlossary() {
        _state.update { it.copy(glossary = it.glossary.copy(visible = false)) }
    }

    fun dismissGlossaryMessage() {
        _state.update { it.copy(glossary = it.glossary.copy(message = null)) }
    }

    fun saveGlossaryTarget(entry: GlossaryEntry, newTarget: String) {
        val id = currentNovelId.takeIf { it.isNotBlank() } ?: return
        GlossaryTerms.validate(entry.source, newTarget)?.let { return showGlossaryMessage(it) }
        val repo = termMemoryRepository ?: return showGlossaryMessage("Không mở được kho thuật ngữ.")
        scope.launch {
            runCatching {
                val localId = entry.localId
                if (localId != null) repo.updateTarget(localId, newTarget) else repo.addManual(id, entry.source, newTarget)
                showGlossaryMessage("Đã lưu “${entry.source}” → “${newTarget.trim()}”.")
            }.onFailure { showGlossaryMessage("Lỗi lưu thuật ngữ: ${it.message}") }
        }
    }

    fun toggleGlossaryPin(entry: GlossaryEntry) {
        val localId = entry.localId ?: return
        val repo = termMemoryRepository ?: return
        scope.launch { runCatching { repo.setPinned(localId, !entry.pinned) } }
    }

    fun deleteGlossaryTerm(entry: GlossaryEntry) {
        val localId = entry.localId ?: return
        val repo = termMemoryRepository ?: return
        scope.launch {
            runCatching {
                repo.delete(localId)
                showGlossaryMessage("Đã xoá “${entry.source}”.")
            }
        }
    }

    fun addGlossaryTerm(source: String, target: String) {
        val id = currentNovelId.takeIf { it.isNotBlank() } ?: return
        GlossaryTerms.validate(source, target)?.let { return showGlossaryMessage(it) }
        val repo = termMemoryRepository ?: return showGlossaryMessage("Không mở được kho thuật ngữ.")
        scope.launch {
            runCatching {
                repo.addManual(id, source, target)
                showGlossaryMessage("Đã thêm “${source.trim()}” → “${target.trim()}”.")
            }.onFailure { showGlossaryMessage("Lỗi thêm thuật ngữ: ${it.message}") }
        }
    }

    fun clearAllGlossaryTerms() {
        val id = currentNovelId.takeIf { it.isNotBlank() } ?: return
        val repo = termMemoryRepository ?: return
        scope.launch {
            runCatching {
                repo.clearNovel(id)
                showGlossaryMessage("Đã xoá thuật ngữ riêng của truyện này.")
            }
        }
    }

    private fun publishGlossary(loading: Boolean = _state.value.glossary.isLoading) {
        val merged = GlossaryMerge.merge(localTerms, serverTerms)
        _state.update { s ->
            s.copy(
                glossaryCount = merged.size,
                glossary = s.glossary.copy(entries = merged, isLoading = loading, serverNotice = serverNotice)
            )
        }
    }

    private fun showGlossaryMessage(msg: String) {
        _state.update { it.copy(glossary = it.glossary.copy(message = msg)) }
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(4_500L)
            _state.update { it.copy(glossary = it.glossary.copy(message = null)) }
        }
    }

    fun openVoicePicker() { _state.update { it.copy(showVoicePicker = true) } }
    fun closeVoicePicker() { _state.update { it.copy(showVoicePicker = false) } }

    fun selectVoiceProfile(profileId: String) {
        val u = userRepository
        if (u != null) {
            val p = u.profile.value
            u.updateTtsPreferences(speed = p.ttsSpeed, autoScroll = p.ttsAutoScroll, voiceProfileId = profileId)
        }
        val heard = TtsVoiceChoices.heard(profileId, _state.value.ttsVoiceTone)
        ttsSpeaker?.setVoiceProfile(heard.id)
        _state.update { it.copy(ttsVoiceProfileId = heard.id, ttsVoiceTone = heard.name, showVoicePicker = false) }
    }

    fun prerenderAudioRange(novelId: String, fromChapter: Int, toChapter: Int) {
        if (novelId.isBlank()) return
        val voice = _state.value.activeVoice
        val count = (toChapter - fromChapter + 1).coerceIn(1, 20)
        prerenderJob?.cancel()
        prerenderJob = scope.launch {
            onBatchFeedback(true, 0.2f, "Đang yêu cầu máy chủ dựng sẵn giọng ${voice.name} (Ch.$fromChapter → Ch.${fromChapter + count - 1})…")
            val resp = prerenderApi.prerender(
                TtsPrerenderRequest(
                    novel_id = novelId,
                    chapter_number = fromChapter,
                    count = count,
                    voice = voice.id,
                    lang = "vi",
                    priority = 1
                )
            )
            if (resp != null) {
                val ready = resp.chapters.count { it.state == "ready" || it.can_start == true }
                onBatchFeedback(
                    false,
                    1f,
                    "Đã xếp dựng Audio giọng ${voice.name} cho $count chương (từ Ch.$fromChapter, $ready chương sẵn sàng phát ngay)!"
                )
            } else {
                onBatchFeedback(false, null, "Lỗi yêu cầu dựng sẵn Audio trên máy chủ.")
            }
        }
    }

    fun openVocabSheet() { _state.update { it.copy(showVocabSheet = true) } }
    fun closeVocabSheet() { _state.update { it.copy(showVocabSheet = false) } }

    fun reviewWord(wordId: String, rating: Int) {
        val repo = vocabularyRepository ?: return
        scope.launch { runCatching { repo.reviewWord(wordId, rating) } }
    }

    fun speakWord(word: String) {
        ttsSpeaker?.speakWord(word)
    }
}
