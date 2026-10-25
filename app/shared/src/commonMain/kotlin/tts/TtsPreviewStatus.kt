package com.truyencloud.shared.tts

/** Lời báo trạng thái nghe thử giọng ở Cài đặt, dùng chung cho Android, iOS và màn hình Cài đặt. */
object TtsPreviewStatus {
    const val FINISHED = "Đã nghe thử xong."

    /** Giọng AI online không phát được; lượt nghe thử chuyển sang giọng máy (như phiên đọc truyện). */
    const val ONLINE_FAILED = "Không kết nối được giọng AI online nên đang nghe thử bằng giọng máy. Hãy kiểm tra mạng hoặc địa chỉ máy chủ."

    /** Cả giọng online lẫn giọng máy đều không phát được. */
    const val FAILED = "Không nghe thử được: giọng đọc không phản hồi. Hãy kiểm tra mạng, địa chỉ máy chủ hoặc gói giọng tiếng Việt của thiết bị."

    /** Lượt nghe thử tự kết thúc: có lỗi / ghi chú thì báo lỗi đó, không thì "đã xong". */
    fun endMessage(error: String?): String = error ?: FINISHED
}
