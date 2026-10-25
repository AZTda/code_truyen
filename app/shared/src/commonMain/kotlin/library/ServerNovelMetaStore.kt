package com.truyencloud.shared.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Metadata máy chủ của từng truyện (số chương đã dịch sẵn trên MiniPC, trạng thái dịch qua đêm, thể loại)
 * nhận kèm qua `/v1/progress` để Tủ sách hiển thị huy hiệu mà không cần sửa bảng Room `novels`.
 */
data class ServerNovelMeta(
    val translatedChapters: Int = 0,
    val overnightEnabled: Boolean = false,
    val category: String = ""
)

object ServerNovelMetaStore {
    private val _state = MutableStateFlow<Map<String, ServerNovelMeta>>(emptyMap())

    val state: StateFlow<Map<String, ServerNovelMeta>> = _state.asStateFlow()
    val flow: StateFlow<Map<String, ServerNovelMeta>> get() = state

    fun record(
        novelId: String,
        translatedChapters: Int,
        overnightEnabled: Boolean,
        category: String = ""
    ) {
        if (novelId.isBlank()) return
        _state.update { current ->
            val existing = current[novelId]
            val next = ServerNovelMeta(
                translatedChapters = translatedChapters.coerceAtLeast(0),
                overnightEnabled = overnightEnabled,
                category = category.ifBlank { existing?.category.orEmpty() }
            )
            current + (novelId to next)
        }
    }

    fun get(novelId: String): ServerNovelMeta? = _state.value[novelId]

    fun clear() {
        _state.value = emptyMap()
    }
}
