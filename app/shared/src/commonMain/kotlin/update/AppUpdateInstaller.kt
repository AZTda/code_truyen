package com.truyencloud.shared.update

/**
 * Yêu cầu tải bản cập nhật. `downloadUrl` là giá trị THÔ máy chủ trả về — trình cài đặt tự kiểm tra
 * lại bằng [UpdateUrlPolicy] (cả URL đầu lẫn từng bước chuyển hướng) thay vì tin người gọi đã kiểm tra.
 */
class ApkDownloadRequest(
    val baseUrl: String,
    val downloadUrl: String,
    /** SHA-256 hex trong manifest; rỗng = máy chủ không cung cấp. */
    val expectedSha256: String,
    /** Dung lượng trong manifest (byte); 0 = không biết. */
    val expectedSizeBytes: Long
)

/**
 * Xử lý kiểm tra phiên bản và kích hoạt cài đặt gói APK trực tiếp — expect/actual cho Android và iOS.
 * iOS không có cập nhật tại chỗ: [downloadAndInstallApk] ở đó chỉ báo lỗi, không mở URL nào.
 */
expect object AppUpdateInstaller {
    /** Nền tảng đang chạy — quyết định tham số `platform` gửi máy chủ và việc có hiện luồng OTA hay không. */
    val platform: UpdatePlatform

    /** Lấy versionCode hiện tại của ứng dụng đã cài đặt trên máy. */
    fun getCurrentVersionCode(): Int

    /** Lấy versionName hiện tại (ví dụ "1.0.0"). */
    fun getCurrentVersionName(): String

    /**
     * Tải file APK, kiểm tra toàn vẹn (SHA-256 + dung lượng) rồi mới kích hoạt màn hình cài đặt của hệ điều hành.
     * Tệp không khớp bị xoá và trình cài đặt KHÔNG bao giờ được mở.
     *
     * @param onProgress Callback báo phần trăm tải (0.0f .. 1.0f)
     * @param onError Callback báo lỗi nếu có
     */
    suspend fun downloadAndInstallApk(
        request: ApkDownloadRequest,
        onProgress: (Float) -> Unit,
        onError: (String) -> Unit
    )
}
