package com.truyencloud.shared.text

/**
 * Tách văn bản chương thành hai tầng khác nhau cho hai mục đích khác nhau.
 *
 * VÌ SAO CẦN:
 * Bản cũ có một hàm `extractParagraphs()` duy nhất cắt mọi đoạn dài hơn 300 ký tự
 * thành các mảnh ~250 ký tự. Việc này phục vụ TTS (đọc từng mảnh ngắn, tô sáng mượt)
 * nhưng gây hai tác hại:
 *
 *  1. Phá bố cục đọc — một đoạn văn học 600 ký tự hiển thị thành 3 đoạn rời rạc.
 *     Với ứng dụng định vị "văn học", đây là mất mát đáng kể.
 *  2. Lệch cặp song ngữ — chế độ song ngữ ghép `englishParagraphs[i]` với
 *     `paragraphs[i]`. Bản Việt và bản Anh dài khác nhau nên cắt ra SỐ MẢNH
 *     KHÁC NHAU, và từ mảnh đầu tiên trở đi là ghép sai câu.
 *
 * Nay tách rõ:
 *  - [splitDisplayParagraphs]: giữ nguyên đoạn gốc → dùng để hiển thị và ghép song ngữ
 *  - [buildTtsSegments]: mảnh nhỏ kèm chỉ số đoạn cha → dùng cho giọng đọc và tô sáng
 */
object ParagraphSplitter {

    /** Độ dài tối đa của một mảnh TTS. */
    const val TTS_SEGMENT_MAX = 250

    /** Đoạn dài hơn mức này mới cần cắt nhỏ cho TTS. */
    const val TTS_SPLIT_THRESHOLD = 300

    /** Một mảnh văn bản dành cho giọng đọc, có tham chiếu ngược về đoạn hiển thị. */
    data class TtsSegment(
        val text: String,
        /** Chỉ số đoạn hiển thị chứa mảnh này — dùng để tô sáng đúng đoạn. */
        val paragraphIndex: Int
    )

    private val htmlBr = Regex("(?i)<br\\s*/?>")
    private val htmlPClose = Regex("(?i)</p>")
    private val htmlTag = Regex("<[^>]+>")
    private val sentenceBoundary = Regex("(?<=[.!?……。！？])\\s+")

    /**
     * Tách thành các đoạn HIỂN THỊ, giữ nguyên ranh giới đoạn của tác giả.
     * Không bao giờ cắt nhỏ một đoạn.
     */
    fun splitDisplayParagraphs(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return cleanLines(text).ifEmpty { listOf(text.trim()) }
    }

    /** Các dòng có chữ sau khi bỏ thẻ HTML và chuẩn hoá xuống dòng (không có bước "rỗng thì giữ nguyên"). */
    private fun cleanLines(text: String): List<String> = text
        .replace(htmlBr, "\n")
        .replace(htmlPClose, "\n")
        .replace(htmlTag, "")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .split("\n")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    /** Đoạn HIỂN THỊ của trình đọc: tách theo dòng trống ("\n\n"), một đoạn có thể còn xuống dòng đơn bên trong. */
    fun splitReaderParagraphs(content: String): List<String> =
        content.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }

    /**
     * Dựng danh sách mảnh cho giọng đọc từ các đoạn hiển thị.
     * Đoạn ngắn giữ nguyên; đoạn dài được cắt theo ranh giới câu.
     *
     * Mỗi đoạn được tách tiếp theo từng DÒNG như [splitDisplayParagraphs]: máy chủ dựng sẵn giọng theo đúng phép cắt đó
     * trên cả chương (server/services/tts_segments.py), nên chữ của từng mảnh phải trùng từng ký tự thì mới trúng tệp
     * đã dựng; đoạn của trình đọc ([splitReaderParagraphs]) có thể chứa xuống dòng đơn.
     */
    fun buildTtsSegments(displayParagraphs: List<String>): List<TtsSegment> {
        val segments = mutableListOf<TtsSegment>()
        val lines = displayParagraphs.map(::cleanLines)
        // Cả chương chỉ toàn thẻ / ký tự trống: giữ nguyên từng đoạn như trước (máy chủ cũng giữ nguyên văn bản)
        val useLines = lines.any { it.isNotEmpty() }
        displayParagraphs.forEachIndexed { paragraphIndex, paragraph ->
            for (line in if (useLines) lines[paragraphIndex] else listOf(paragraph)) {
                appendSegments(segments, line, paragraphIndex)
            }
        }
        return segments
    }

    private fun appendSegments(segments: MutableList<TtsSegment>, paragraph: String, paragraphIndex: Int) {
        if (paragraph.length <= TTS_SPLIT_THRESHOLD) {
            segments += TtsSegment(paragraph, paragraphIndex)
            return
        }
        val sentences = paragraph.split(sentenceBoundary)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (sentences.isEmpty()) {
            segments += TtsSegment(paragraph, paragraphIndex)
            return
        }
        val buffer = StringBuilder()
        for (sentence in sentences) {
            if (buffer.isNotEmpty() && buffer.length + sentence.length > TTS_SEGMENT_MAX) {
                segments += TtsSegment(buffer.toString().trim(), paragraphIndex)
                buffer.clear()
            }
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(sentence)
        }
        if (buffer.isNotEmpty()) {
            segments += TtsSegment(buffer.toString().trim(), paragraphIndex)
        }
    }

    /** Chỉ lấy phần văn bản của các mảnh — dạng mà TtsSpeaker đang nhận. */
    fun ttsTexts(segments: List<TtsSegment>): List<String> = segments.map { it.text }

    /**
     * Đổi chỉ số mảnh TTS đang đọc sang chỉ số đoạn hiển thị, để tô sáng đúng
     * đoạn mà người đọc nhìn thấy.
     */
    fun paragraphIndexForSegment(segments: List<TtsSegment>, segmentIndex: Int): Int =
        segments.getOrNull(segmentIndex)?.paragraphIndex ?: 0

    /** Mảnh TTS đầu tiên thuộc một đoạn hiển thị — dùng khi người dùng chạm vào đoạn để nghe. */
    fun firstSegmentOfParagraph(segments: List<TtsSegment>, paragraphIndex: Int): Int {
        // Đoạn không có mảnh nào (chỉ có thẻ HTML) thì đọc từ đoạn có chữ kế tiếp, không quay về đầu chương
        val idx = segments.indexOfFirst { it.paragraphIndex >= paragraphIndex }
        return if (idx >= 0) idx else 0
    }

    data class BilingualContent(
        val englishParagraphs: List<String>,
        val vietnameseParagraphs: List<String>
    )

    /**
     * Tách văn bản song ngữ có gắn thẻ [EN] và [VI] thành 2 danh sách tương ứng.
     */
    fun splitBilingualPairs(text: String): BilingualContent? {
        if (!text.contains("[EN]", ignoreCase = true) || !text.contains("[VI]", ignoreCase = true)) return null

        val enList = mutableListOf<String>()
        val viList = mutableListOf<String>()

        val lines = text.split("\n")
        var currentTag: String? = null
        val currentBuffer = StringBuilder()

        fun flush() {
            val content = currentBuffer.toString().trim()
            if (content.isNotBlank()) {
                if (currentTag == "EN") enList.add(content)
                else if (currentTag == "VI") viList.add(content)
            }
            currentBuffer.clear()
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[EN]", ignoreCase = true)) {
                flush()
                currentTag = "EN"
                currentBuffer.append(trimmed.substring(4).trim())
            } else if (trimmed.startsWith("[VI]", ignoreCase = true)) {
                flush()
                currentTag = "VI"
                currentBuffer.append(trimmed.substring(4).trim())
            } else if (trimmed.isNotBlank()) {
                if (currentBuffer.isNotEmpty()) currentBuffer.append("\n")
                currentBuffer.append(trimmed)
            }
        }
        flush()

        if (enList.isNotEmpty() && viList.isNotEmpty()) {
            return BilingualContent(enList, viList)
        }
        return null
    }

    /**
     * Ghép đoạn song ngữ: đoạn thứ i của bản hiển thị (tiếng Anh)
     * tương ứng trực tiếp 1-1 với đoạn thứ i của bản đối chiếu (tiếng Việt).
     *
     * TUYỆT ĐỐI KHÔNG chia tỉ lệ (ratio * size) vì sẽ gây ra hiện tượng:
     * - Đoạn đầu bị nhân đôi (đoạn 0 và đoạn 1 cùng nhận đoạn Việt 0).
     * - Toàn bộ các đoạn phía sau bị dịch chuyển lệch hẳn so với bản gốc.
     */
    fun pairBilingual(
        primaryIndex: Int,
        primaryCount: Int,
        secondary: List<String>
    ): String? {
        if (secondary.isEmpty() || primaryCount <= 0) return null
        return secondary.getOrNull(primaryIndex)
    }
}
