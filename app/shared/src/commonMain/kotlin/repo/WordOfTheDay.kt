package com.truyencloud.shared.repo

import com.truyencloud.shared.data.SavedWord

/**
 * Chọn "Từ vựng hôm nay" từ kho từ vựng HIỆN TẠI của tài khoản: một từ cố định trong ngày, xoay vòng theo số ngày.
 *
 * Tách ra thành hàm thuần để kiểm thử được. Bản cũ chỉ nạp một lần lúc màn hình chính khởi tạo, mà màn hình đó sống suốt
 * phiên chạy của app: xoá dữ liệu trên máy hay đổi tài khoản xong thẻ vẫn giữ từ của tài khoản trước tới khi tắt app.
 */
object WordOfTheDay {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** null khi kho trống: thẻ phải biến mất, không được giữ lại từ của lần nạp trước. */
    fun pick(words: List<SavedWord>, nowMillis: Long): SavedWord? {
        if (words.isEmpty()) return null
        val index = ((nowMillis / DAY_MS) % words.size).toInt()
        return words[index]
    }
}
