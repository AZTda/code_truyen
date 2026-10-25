package com.truyencloud.shared.library

/** Định dạng hiển thị thuần (không String.format vì KMP commonMain không có). */
object LibraryFormat {
    private const val KB = 1024L
    private const val MB = KB * 1024
    private const val GB = MB * 1024

    /** Cùng kiểu với màn Dung lượng trong Cài đặt: "512 KB", "3.4 MB", "1.25 GB". */
    fun bytes(bytes: Long): String = when {
        bytes <= 0L -> "0 KB"
        bytes < KB -> "1 KB"
        bytes < MB -> "${bytes / KB} KB"
        bytes < GB -> {
            val tenths = bytes * 10 / MB
            "${tenths / 10}.${tenths % 10} MB"
        }
        else -> {
            val hundredths = bytes * 100 / GB
            "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} GB"
        }
    }

    /** "Còn 12 chương chưa đọc" / null khi không còn gì. */
    fun unreadLabel(unread: Int): String? = if (unread > 0) "Còn $unread chương" else null
}
