package com.truyencloud.compose.screens.reader

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bọc [raw] để MỌI nơi ghi trạng thái màn đọc (các coordinator đều nhận bản bọc này) đi qua một chốt chặn: một lần
 * cập nhật không được xoá trắng đoạn văn của CHÍNH chương đang hiển thị, kẻo chữ đang đọc biến mất vì một lần ghi hụt.
 * Sang chương khác (id khác) thì được phép làm rỗng như bình thường.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class GuardedReaderState(
    private val raw: MutableStateFlow<ReaderUiState>
) : MutableStateFlow<ReaderUiState> by raw {

    override var value: ReaderUiState
        get() = raw.value
        set(v) { raw.value = guard(raw.value, v) }

    override fun compareAndSet(expect: ReaderUiState, update: ReaderUiState): Boolean =
        raw.compareAndSet(expect, guard(raw.value, update))

    private fun guard(current: ReaderUiState, next: ReaderUiState): ReaderUiState =
        if (current.paragraphs.isNotEmpty() && next.paragraphs.isEmpty() &&
            next.currentChapter?.id == current.currentChapter?.id
        ) {
            next.copy(paragraphs = current.paragraphs, isLoadingChapter = false)
        } else {
            next
        }
}
