package com.truyencloud.shared.export

/**
 * Xử lý chia sẻ tệp xuất khẩu — expect/actual cho Android và iOS.
 */
expect object PlatformShareHandler {
    /**
     * Chia sẻ nội dung văn bản dưới dạng file qua Share Sheet / Intent.
     * @param fileName  Tên file (e.g. "truyencloud_vocab.csv")
     * @param content   Nội dung file
     * @param mimeType  MIME type (e.g. "text/csv")
     */
    fun shareTextFile(fileName: String, content: String, mimeType: String)

    /** Chia sẻ tệp nhị phân (ví dụ `.epub`) qua Share Sheet / Intent. */
    fun shareBinaryFile(fileName: String, bytes: ByteArray, mimeType: String)
}
