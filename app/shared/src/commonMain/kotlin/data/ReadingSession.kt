package com.truyencloud.shared.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "reading_sessions")
@Serializable
data class ReadingSession(
    @PrimaryKey val date: String, // Format: YYYY-MM-DD
    val minutesRead: Int = 0,
    val chaptersRead: Int = 0,
    val wordsRead: Int = 0,
    val lastUpdatedAt: Long = 0L
)
