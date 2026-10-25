package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.reader.BilingualPair
import com.truyencloud.shared.reader.BilingualPairs
import com.truyencloud.shared.reader.ReaderTranslationFailure
import com.truyencloud.shared.reader.TranslationFailureKind
import com.truyencloud.shared.repo.TranslateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Chế độ song ngữ căn hàng.
 *
 * Bản cũ chạy bản dịch tiếng Anh RIÊNG rồi ghép với bản dịch tiếng Việt RIÊNG theo chỉ số đoạn — hai bản có số đoạn khác
 * nhau nên càng về sau càng lệch hàng. Nay xin máy chủ đích `bilingual` (prompt song ngữ trả từng cặp `[EN]`/`[VI]`) và
 * hiển thị đúng các cặp đó; có sẵn trong máy thì dùng luôn (đọc offline được).
 */
internal class ReaderBilingualAdapter(
    private val translateRepository: TranslateRepository,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val onContentUpdated: () -> Unit
) {
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun adapt() {
        val s = uiStateFlow.value
        val chapter = s.currentChapter ?: return
        val novel = s.novel ?: return
        val level = s.englishLevel
        val number = chapter.chapterNumber

        job?.cancel()
        uiStateFlow.value = s.copy(isAdaptingEnglish = true, englishNotice = null)
        job = scope.launch {
            try {
                val cached = translateRepository.getCachedBilingual(novel.id, number, level)
                if (cached != null) {
                    val pairs = BilingualPairs.parse(cached)
                    if (BilingualPairs.isUsable(pairs)) return@launch applyPairs(novel.id, number, pairs)
                }
                if (TruyenCloudApi.authToken.isBlank()) {
                    return@launch fail(novel.id, number, ReaderTranslationFailure.message(TranslationFailureKind.LOGIN_REQUIRED))
                }
                val result = translateRepository.translateChapterDetailed(
                    novelId = novel.id,
                    chapterNumber = number,
                    content = chapter.originalContent,
                    force = false,
                    targetLanguage = "bilingual",
                    englishLevel = level
                )
                val pairs = BilingualPairs.parse(result.text)
                if (result.isFallback || !BilingualPairs.isUsable(pairs)) {
                    fail(novel.id, number, "Chưa tạo được bản song ngữ căn hàng cho chương này")
                } else {
                    applyPairs(novel.id, number, pairs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(novel.id, number, ReaderTranslationFailure.messageFor(e))
            }
        }
    }

    private fun stillCurrent(novelId: String, number: Int): Boolean {
        val s = uiStateFlow.value
        return s.novel?.id == novelId && s.currentChapter?.chapterNumber == number
    }

    private fun applyPairs(novelId: String, number: Int, pairs: List<BilingualPair>) {
        if (!stillCurrent(novelId, number)) return
        val s = uiStateFlow.value
        val enParas = pairs.map { it.english }
        uiStateFlow.value = s.copy(
            isAdaptingEnglish = false,
            englishParagraphs = enParas,
            bilingualViParagraphs = pairs.map { it.vietnamese },
            englishNotice = null
        )
        onContentUpdated()
        triggerGrammarExtractionAsync(
            scope = scope,
            paragraphs = enParas,
            novelId = novelId,
            chapterNumber = number,
            novelTitle = s.novel?.titleVi?.ifBlank { s.novel.title }.orEmpty()
        )
    }

    private fun fail(novelId: String, number: Int, message: String) {
        if (!stillCurrent(novelId, number)) return
        uiStateFlow.value = uiStateFlow.value.copy(
            isAdaptingEnglish = false,
            isBilingualMode = false,
            englishNotice = "$message. Đang giữ bản tiếng Việt."
        )
        onContentUpdated()
    }
}

internal fun extractEnglishParagraphs(content: String): List<String> =
    content.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }

internal fun isVietnameseOnlyText(text: String): Boolean {
    val sample = text.take(200)
    val viDiacritics = Regex("[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]", RegexOption.IGNORE_CASE)
    return viDiacritics.findAll(sample).count() > 12
}

/** Ghi nhận ngầm các mẫu ngữ pháp C1/C2 của chương tiếng Anh vừa chuyển thể để Thống kê tự động có dữ liệu. */
internal fun triggerGrammarExtractionAsync(
    scope: CoroutineScope,
    paragraphs: List<String>,
    novelId: String,
    chapterNumber: Int,
    novelTitle: String
) {
    if (paragraphs.isEmpty() || TruyenCloudApi.authToken.isBlank()) return
    val sampleText = paragraphs.take(12).joinToString("\n\n").take(4500)
    if (sampleText.length < 80) return
    scope.launch {
        try {
            com.truyencloud.shared.api.EnglishApi().extractGrammarPatterns(
                text = sampleText,
                novelId = novelId,
                chapterNumber = chapterNumber,
                novelTitle = novelTitle,
                autoRecord = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
