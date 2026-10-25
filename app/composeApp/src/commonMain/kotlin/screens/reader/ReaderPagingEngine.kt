package com.truyencloud.compose.screens.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.shared.text.ParagraphSplitter

/**
 * Một phần của đoạn văn được xếp vào một trang cụ thể.
 * Khi một đoạn văn dài tràn trang, nó sẽ được chia thành nhiều chunk.
 * subText lưu trữ nội dung bản dịch đối chiếu (tiếng Việt khi ở chế độ song ngữ).
 */
data class PagedChunk(
    val paragraphIndex: Int,
    val text: String,
    val isStartOfParagraph: Boolean,
    val isEndOfParagraph: Boolean,
    val subText: String? = null
)

/**
 * Biểu diễn một trang sách hoàn chỉnh với chiều cao cố định vừa khít thiết bị.
 */
data class BookPage(
    val pageIndex: Int,
    val hasChapterHeader: Boolean = false,
    val chunks: List<PagedChunk>
)

object ReaderPagingEngine {

    /**
     * Phân trang toàn bộ chương truyện thành các trang sách lấp đầy chiều cao màn hình.
     *
     * @param paragraphs Danh sách các đoạn văn của chương (hoặc tiếng Anh nếu ở chế độ song ngữ)
     * @param title Tiêu đề chương (chỉ đo và vẽ ở trang 0)
     * @param availableWidthPx Chiều rộng khả dụng cho chữ (đã trừ padding lề)
     * @param availableHeightPx Chiều cao khả dụng cho chữ (đã trừ top bar và footer)
     * @param textMeasurer Bộ đo văn bản Compose
     * @param bodyStyle Kiểu chữ cho nội dung truyện
     * @param titleStyle Kiểu chữ cho tiêu đề chương ở trang 1
     * @param density Mật độ điểm ảnh màn hình
     * @param paragraphSpacingDp Khoảng cách giữa 2 đoạn văn
     * @param subStyle Kiểu chữ cho đoạn dịch phụ (song ngữ)
     * @param secondaryParagraphs Danh sách các đoạn văn bản dịch đối chiếu (tiếng Việt)
     */
    fun paginateChapter(
        paragraphs: List<String>,
        title: String,
        availableWidthPx: Int,
        availableHeightPx: Int,
        textMeasurer: TextMeasurer,
        bodyStyle: TextStyle,
        titleStyle: TextStyle,
        density: Density,
        paragraphSpacingDp: Dp = 12.dp,
        subStyle: TextStyle? = null,
        secondaryParagraphs: List<String>? = null
    ): List<BookPage> {
        if (paragraphs.isEmpty() || availableWidthPx <= 10 || availableHeightPx <= 50) {
            return listOf(BookPage(pageIndex = 0, hasChapterHeader = true, chunks = emptyList()))
        }

        val paragraphSpacingPx = with(density) { paragraphSpacingDp.roundToPx() }
        val subSpacerPx = with(density) { 4.dp.roundToPx() }

        // Đo chiều cao tiêu đề chương và các thành phần phụ ở trang đầu (divider, button, spacing)
        val titleMeasure = textMeasurer.measure(
            text = AnnotatedString(title),
            style = titleStyle,
            constraints = Constraints(maxWidth = availableWidthPx)
        )
        // Phần ngoài tiêu đề ở trang đầu: đệm dưới tên chương 4 + hàng nút chữ 48 + đệm 4 (ReaderChapterHeader) + đệm 14 của ReaderPagedPage
        val headerExtraHeightPx = with(density) { 72.dp.roundToPx() }
        val page0HeaderTotalPx = titleMeasure.size.height + headerExtraHeightPx

        val pages = mutableListOf<BookPage>()
        var currentPageChunks = mutableListOf<PagedChunk>()
        var currentHeightUsed = 0
        var isFirstPage = true
        var maxPageHeight = (availableHeightPx - page0HeaderTotalPx).coerceAtLeast(160)

        fun finishCurrentPage() {
            pages.add(
                BookPage(
                    pageIndex = pages.size,
                    hasChapterHeader = isFirstPage,
                    chunks = currentPageChunks.toList()
                )
            )
            currentPageChunks = mutableListOf()
            currentHeightUsed = 0
            isFirstPage = false
            maxPageHeight = availableHeightPx
        }

        for ((pIdx, rawParagraph) in paragraphs.withIndex()) {
            val pText = rawParagraph.trim()
            if (pText.isEmpty()) continue

            val subText = if (secondaryParagraphs != null && secondaryParagraphs.isNotEmpty() && subStyle != null) {
                ParagraphSplitter.pairBilingual(
                    primaryIndex = pIdx,
                    primaryCount = paragraphs.size,
                    secondary = secondaryParagraphs
                )?.trim()?.takeIf { it.isNotEmpty() }
            } else null

            val layout = textMeasurer.measure(
                text = AnnotatedString(pText),
                style = bodyStyle,
                constraints = Constraints(maxWidth = availableWidthPx)
            )

            val lineCount = layout.lineCount
            if (lineCount == 0) continue

            val subLayout = if (subText != null && subStyle != null) {
                textMeasurer.measure(
                    text = AnnotatedString(subText),
                    style = subStyle,
                    constraints = Constraints(maxWidth = availableWidthPx)
                )
            } else null

            val subHeightTotalPx = if (subLayout != null) subLayout.size.height + subSpacerPx else 0
            val paragraphContentHeightPx = layout.size.height + subHeightTotalPx
            val fullParagraphHeightPx = paragraphContentHeightPx + paragraphSpacingPx

            // TRƯỜNG HỢP 1: Cả đoạn chính và bản dịch phụ vừa khít khoảng trống còn lại của trang hiện tại
            // (Chỉ cần paragraphContentHeightPx nếu đây là đoạn cuối cùng của trang)
            if (paragraphContentHeightPx <= (maxPageHeight - currentHeightUsed)) {
                currentPageChunks.add(
                    PagedChunk(
                        paragraphIndex = pIdx,
                        text = pText,
                        isStartOfParagraph = true,
                        isEndOfParagraph = true,
                        subText = subText
                    )
                )
                currentHeightUsed += fullParagraphHeightPx
                if (currentHeightUsed >= maxPageHeight) {
                    finishCurrentPage()
                }
                continue
            }

            // TRƯỜNG HỢP 2: Không vừa khoảng trống còn lại, NHƯNG vừa một trang mới hoàn chỉnh (availableHeightPx), và trang hiện tại đã có nội dung
            if (paragraphContentHeightPx <= availableHeightPx && currentPageChunks.isNotEmpty()) {
                finishCurrentPage()
                currentPageChunks.add(
                    PagedChunk(
                        paragraphIndex = pIdx,
                        text = pText,
                        isStartOfParagraph = true,
                        isEndOfParagraph = true,
                        subText = subText
                    )
                )
                currentHeightUsed += fullParagraphHeightPx
                if (currentHeightUsed >= maxPageHeight) {
                    finishCurrentPage()
                }
                continue
            }

            // TRƯỜNG HỢP 3: Đoạn văn dài hoặc không thể đặt trọn vẹn, cần chia dòng
            var lineStart = 0
            while (lineStart < lineCount) {
                val remainingHeight = maxPageHeight - currentHeightUsed
                val firstLineHeight = (layout.getLineBottom(lineStart) - layout.getLineTop(lineStart)).toInt()

                // Nếu dòng đầu tiên không vừa và trang hiện tại đã có chữ, đóng trang này lại
                if (firstLineHeight > remainingHeight && currentPageChunks.isNotEmpty()) {
                    finishCurrentPage()
                    continue
                }

                // Tích lũy tối đa các dòng có thể chứa trong remainingHeight
                var lineEnd = lineStart
                var chunkHeight = 0

                while (lineEnd < lineCount) {
                    val candidateHeight = (layout.getLineBottom(lineEnd) - layout.getLineTop(lineStart)).toInt()
                    val isLastLine = (lineEnd + 1 >= lineCount)
                    val estSubPx = if (subHeightTotalPx > 0) {
                        if (isLastLine && lineStart == 0) subHeightTotalPx
                        else {
                            val lineRatio = ((lineEnd - lineStart + 1).toFloat() / lineCount.toFloat()).coerceIn(0.25f, 1f)
                            (subHeightTotalPx * lineRatio).toInt() + subSpacerPx
                        }
                    } else 0
                    if (candidateHeight + estSubPx <= (maxPageHeight - currentHeightUsed)) {
                        chunkHeight = candidateHeight
                        lineEnd++
                    } else {
                        if (lineEnd == lineStart && candidateHeight <= (maxPageHeight - currentHeightUsed)) {
                            chunkHeight = candidateHeight
                            lineEnd++
                        }
                        break
                    }
                }

                // Đảm bảo ít nhất 1 dòng được xếp vào trang để tránh lặp vô tận
                if (lineEnd == lineStart) {
                    lineEnd = lineStart + 1
                    chunkHeight = (layout.getLineBottom(lineStart) - layout.getLineTop(lineStart)).toInt()
                }

                val charStart = layout.getLineStart(lineStart).coerceIn(0, pText.length)
                val charEnd = layout.getLineEnd(lineEnd - 1).coerceIn(charStart, pText.length)
                val isStartOfParagraph = (lineStart == 0)
                val isEndOfParagraph = (lineEnd >= lineCount)

                val rawChunk = pText.substring(charStart, charEnd)
                val chunkText = if (isStartOfParagraph) rawChunk.trimEnd() else rawChunk.trim()

                // Phân phối phần dịch tương ứng với đoạn văn bản
                val thisSubText = if (subText != null) {
                    extractBilingualSubChunk(subText, charStart, charEnd, pText.length)
                } else null

                val thisSubHeightPx = if (thisSubText != null && subStyle != null) {
                    val sl = textMeasurer.measure(
                        text = AnnotatedString(thisSubText),
                        style = subStyle,
                        constraints = Constraints(maxWidth = availableWidthPx)
                    )
                    sl.size.height + subSpacerPx
                } else 0

                if (chunkText.isNotEmpty() || !thisSubText.isNullOrBlank()) {
                    currentPageChunks.add(
                        PagedChunk(
                            paragraphIndex = pIdx,
                            text = chunkText,
                            isStartOfParagraph = isStartOfParagraph,
                            isEndOfParagraph = isEndOfParagraph,
                            subText = thisSubText
                        )
                    )
                }

                currentHeightUsed += chunkHeight + thisSubHeightPx
                if (isEndOfParagraph) {
                    currentHeightUsed += paragraphSpacingPx
                }

                lineStart = lineEnd

                if (currentHeightUsed >= maxPageHeight) {
                    finishCurrentPage()
                }
            }
        }

        // Đóng trang cuối cùng nếu còn nội dung
        if (currentPageChunks.isNotEmpty() || pages.isEmpty()) {
            pages.add(
                BookPage(
                    pageIndex = pages.size,
                    hasChapterHeader = isFirstPage,
                    chunks = currentPageChunks.toList()
                )
            )
        }

        return pages
    }

    private val sentenceSplitRegex = Regex("(?<=[.!?……。！？])\\s+")

    private fun extractBilingualSubChunk(
        subText: String?,
        startChar: Int,
        endChar: Int,
        totalChars: Int
    ): String? {
        if (subText.isNullOrBlank() || totalChars <= 0) return subText
        val sentences = subText.split(sentenceSplitRegex).filter { it.isNotBlank() }
        if (sentences.size > 1) {
            val startRatio = (startChar.toFloat() / totalChars).coerceIn(0f, 1f)
            val endRatio = (endChar.toFloat() / totalChars).coerceIn(startRatio, 1f)
            val startIdx = (startRatio * sentences.size).toInt().coerceIn(0, sentences.size - 1)
            val endIdx = (endRatio * sentences.size).toInt().coerceIn(startIdx + 1, sentences.size)
            val subList = sentences.subList(startIdx, endIdx)
            if (subList.isNotEmpty()) return subList.joinToString(" ")
        }

        // Nếu chỉ có 1 câu dài, phân chia theo mệnh đề (dấu phẩy, chấm phẩy, gạch nối)
        val clauses = subText.split(Regex("(?<=[,;，；—–])\\s+")).filter { it.isNotBlank() }
        if (clauses.size > 1) {
            val startRatio = (startChar.toFloat() / totalChars).coerceIn(0f, 1f)
            val endRatio = (endChar.toFloat() / totalChars).coerceIn(startRatio, 1f)
            val startIdx = (startRatio * clauses.size).toInt().coerceIn(0, clauses.size - 1)
            val endIdx = (endRatio * clauses.size).toInt().coerceIn(startIdx + 1, clauses.size)
            val subList = clauses.subList(startIdx, endIdx)
            if (subList.isNotEmpty()) return subList.joinToString(" ")
        }

        // Phân chia theo tỷ lệ số từ nếu không có dấu câu
        val words = subText.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > 2) {
            val startRatio = (startChar.toFloat() / totalChars).coerceIn(0f, 1f)
            val endRatio = (endChar.toFloat() / totalChars).coerceIn(startRatio, 1f)
            val startIdx = (startRatio * words.size).toInt().coerceIn(0, words.size - 1)
            val endIdx = (endRatio * words.size).toInt().coerceIn(startIdx + 1, words.size)
            val subList = words.subList(startIdx, endIdx)
            if (subList.isNotEmpty()) return subList.joinToString(" ")
        }

        return if (endChar >= totalChars) subText else null
    }
}
