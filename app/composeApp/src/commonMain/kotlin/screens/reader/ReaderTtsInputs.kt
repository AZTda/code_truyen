package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.tts.TtsSpeaker
import com.truyencloud.shared.tts.TtsStartPolicy

/** Văn bản TTS đọc: bản hoà trộn / Anh / song ngữ nếu đã có, ngược lại bản Việt (cùng bản đang hiển thị). */
internal fun ReaderUiState.ttsSource(): List<String> = when {
    isProgressiveMode && progressiveParagraphs.isNotEmpty() -> progressiveParagraphs
    (isEnglishMode || isBilingualMode) && englishParagraphs.isNotEmpty() -> englishParagraphs
    else -> paragraphs
}

/** Chương đang mở có phải chương cuối đã biết của truyện không. */
internal fun ReaderUiState.isLastKnownChapter(): Boolean {
    val current = currentChapter?.chapterNumber ?: return false
    val last = maxOf(chapters.maxOfOrNull { it.chapterNumber } ?: 0, novel?.totalChapters ?: 0)
    return TtsStartPolicy.isLastChapter(current, last)
}

/** Tên truyện / chương / ảnh bìa cho thông báo và màn hình khoá. */
internal fun TtsSpeaker.applyChapterMetadata(st: ReaderUiState) {
    val novel = st.novel
    val novelTitle = novel?.titleVi?.ifBlank { novel.title } ?: "TruyenCloud"
    val ch = st.currentChapter
    val chapterTitle = if (ch != null) "Chương ${ch.chapterNumber}: ${ch.titleVi.ifBlank { ch.title }}" else "Chương đọc"
    setMetadata(novelTitle, chapterTitle)
    setArtworkUrl(novel?.coverUrl?.takeIf { it.isNotBlank() }?.let { TruyenCloudApi.getImageProxyUrl(it) })
}
