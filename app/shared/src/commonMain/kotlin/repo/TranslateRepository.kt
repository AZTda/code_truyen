package com.truyencloud.shared.repo

import com.truyencloud.shared.api.BatchTranslateResponse
import com.truyencloud.shared.api.JobStatusResponse
import com.truyencloud.shared.api.TermRemote
import com.truyencloud.shared.api.TranslateApi
import com.truyencloud.shared.data.ChapterDao

class TranslateRepository(
    private val translateApi: TranslateApi,
    private val chapterDao: ChapterDao,
    /**
     * Tri nho thuat ngu cua truyen.
     *
     * Duoc nap ngay TAI DAY chu khong o tung cho goi: tham so `glossary` da duoc noi
     * san toi may chu tu lau nhung khong noi goi nao dien vao, nen moi chuong van duoc
     * dich nhu the chua tung co chuong nao truoc do. De tung cho goi tu nap thi 5 cho
     * trong ReaderViewModel va NovelDetailViewModel deu co the quen mot cho, va loi se
     * tai xuat hien am tham.
     */
    private val termMemoryRepository: TermMemoryRepository? = null
) {
    /**
     * Bang thuat ngu thuc su gui len may chu.
     *
     * Cho goi co the tu truyen `glossary` (uu tien), neu khong thi lay tu tri nho
     * cua chinh bo truyen do.
     */
    private suspend fun resolveGlossary(novelId: String, explicit: List<TermRemote>): List<TermRemote> {
        if (explicit.isNotEmpty()) return explicit
        val repo = termMemoryRepository ?: return emptyList()
        return try {
            repo.glossaryFor(novelId)
        } catch (e: Exception) {
            // Thieu bang thuat ngu thi ban dich kem nhat quan, nhung van phai dich duoc.
            e.printStackTrace()
            emptyList()
        }
    }

    /** Kết quả dịch một chương kèm nguồn dịch — để UI báo "bản dịch máy tạm" khi AI không dịch được. */
    data class ChapterTranslation(
        val text: String,
        /** true: không do AI dịch (dự phòng Google); máy chủ sẽ thử AI lại ở lần yêu cầu sau. */
        val isFallback: Boolean,
        val engine: String,
        val titleVi: String = ""
    )

    private val chapterHeadingRegex = Regex(
        """^(?:\*{1,3}|#{1,4}\s*)?(?:chương|hồi|tiết|quyển)\s*(?:thứ\s*)?\d+\s*[:：.\-–—]\s*(.+?)(?:\*{1,3})?$""",
        RegexOption.IGNORE_CASE
    )

    private fun extractTranslatedTitleVi(chapterNumber: Int, explicitTitleVi: String, translatedText: String): String {
        val cleanExplicit = explicitTitleVi.trim()
        if (cleanExplicit.isNotEmpty()) return cleanExplicit
        val firstLines = translatedText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        for (line in firstLines) {
            val m = chapterHeadingRegex.find(line) ?: continue
            val sub = m.groupValues[1].trim().trim('*', '"', '\'', '«', '»', '“', '”', ':', '-', '–', '—')
            if (sub.isNotEmpty() && sub.length <= 100) {
                return "Chương $chapterNumber: $sub"
            }
        }
        return ""
    }

    suspend fun translateChapter(
        novelId: String,
        chapterNumber: Int,
        content: String? = null,
        glossary: List<TermRemote> = emptyList(),
        force: Boolean = false,
        customPrompt: String? = null,
        targetLanguage: String = "vi",
        englishLevel: String = "c1",
        prefetch: Boolean = false
    ): String = translateChapterDetailed(
        novelId, chapterNumber, content, glossary, force, customPrompt, targetLanguage, englishLevel, prefetch
    ).text

    suspend fun translateChapterDetailed(
        novelId: String,
        chapterNumber: Int,
        content: String? = null,
        glossary: List<TermRemote> = emptyList(),
        force: Boolean = false,
        customPrompt: String? = null,
        targetLanguage: String = "vi",
        englishLevel: String = "c1",
        prefetch: Boolean = false
    ): ChapterTranslation {
        val res = translateApi.translateChapter(
            novelId = novelId,
            chapterNumber = chapterNumber,
            content = content,
            glossary = resolveGlossary(novelId, glossary),
            force = force,
            customPrompt = customPrompt,
            targetLanguage = targetLanguage,
            englishLevel = englishLevel,
            prefetch = prefetch
        )

        val engineName = res.translation_engine.ifBlank { res.engine }
        val resolvedTitleVi = if (targetLanguage.lowercase() == "vi" && !res.is_fallback) {
            extractTranslatedTitleVi(chapterNumber, res.title_vi, res.translated_content)
        } else ""

        // Luu ca ban tieng Anh / song ngu vao Room. Ban cu chi luu "vi" nen che do hoc
        // tieng Anh khong doc duoc offline va moi lan mo lai chuong deu phai goi may chu.
        when (targetLanguage.lowercase()) {
            "vi" -> chapterDao.updateTranslation(
                novelId = novelId,
                chapterNumber = chapterNumber,
                translated = res.translated_content,
                engine = engineName,
                titleVi = resolvedTitleVi
            )
            "en", "english" -> chapterDao.updateEnglishTranslation(
                novelId = novelId,
                chapterNumber = chapterNumber,
                content = res.translated_content,
                level = englishLevel.lowercase()
            )
            "bilingual" -> chapterDao.updateBilingualTranslation(
                novelId = novelId,
                chapterNumber = chapterNumber,
                content = res.translated_content,
                level = englishLevel.lowercase()
            )
        }

        return ChapterTranslation(res.translated_content, res.is_fallback, engineName, resolvedTitleVi)
    }

    /** Lay ban tieng Anh da luu san trong may (neu co) — khong cham mang. */
    suspend fun getCachedEnglish(novelId: String, chapterNumber: Int, level: String): String? {
        val ch = chapterDao.getChapter(novelId, chapterNumber) ?: return null
        return if (ch.hasEnglishFor(level)) ch.translatedContentEn else null
    }

    suspend fun getCachedBilingual(novelId: String, chapterNumber: Int, level: String): String? {
        val ch = chapterDao.getChapter(novelId, chapterNumber) ?: return null
        return if (ch.hasBilingualFor(level)) ch.translatedContentBilingual else null
    }

    suspend fun batchTranslate(
        novelId: String,
        fromChapter: Int,
        toChapter: Int,
        glossary: List<TermRemote> = emptyList(),
        overnight: Boolean = false
    ): BatchTranslateResponse {
        return translateApi.batchTranslate(
            novelId,
            fromChapter,
            toChapter,
            resolveGlossary(novelId, glossary),
            overnight = overnight
        )
    }

    suspend fun getBatchJobStatus(jobId: String): JobStatusResponse =
        translateApi.getJobStatus(jobId)

    suspend fun stopOvernightTranslation(novelId: String): BatchTranslateResponse =
        translateApi.stopOvernightTranslation(novelId)

    suspend fun getOvernightStatus() = translateApi.getOvernightStatus()
}
