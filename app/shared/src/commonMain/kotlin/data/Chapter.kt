package com.truyencloud.shared.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "chapters",
    indices = [Index(value = ["novelId", "chapterNumber"], unique = true)]
)
data class Chapter(
    @PrimaryKey
    val id: String,
    val novelId: String,
    val chapterNumber: Int,
    val title: String,
    val titleVi: String = "",
    val url: String = "",
    val originalContent: String? = null,
    val translatedContent: String? = null,
    val translationEngine: String? = "ANTIGRAVITY",

    // ---- Bản tiếng Anh & song ngữ -----------------------------------------
    // Trước đây `TranslateRepository` chỉ lưu bản tiếng Việt vào Room, nên mỗi lần
    // mở lại chương ở chế độ học tiếng Anh đều phải gọi máy chủ và không đọc được
    // khi mất mạng. Nay lưu kèm cấp độ CEFR để đổi cấp độ vẫn dùng được bản đã có.
    @ColumnInfo(defaultValue = "")
    val translatedContentEn: String = "",
    @ColumnInfo(defaultValue = "")
    val englishLevel: String = "",
    @ColumnInfo(defaultValue = "")
    val translatedContentBilingual: String = "",
    @ColumnInfo(defaultValue = "")
    val bilingualLevel: String = "",

    val isRead: Boolean = false,
    val scrollPosition: Float = 0.0f,
    val updatedAt: Long = 0L
) {
    /** Đã có sẵn bản tiếng Anh đúng cấp độ này chưa (đọc offline được). */
    fun hasEnglishFor(level: String): Boolean =
        translatedContentEn.isNotBlank() && englishLevel.equals(level, ignoreCase = true)

    fun hasBilingualFor(level: String): Boolean =
        translatedContentBilingual.isNotBlank() && bilingualLevel.equals(level, ignoreCase = true)
}
