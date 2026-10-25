package com.truyencloud.shared.api

import com.truyencloud.shared.update.UpdatePlatform
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppVersionResponse(
    @SerialName("version_code") val versionCode: Int = 1,
    @SerialName("version_name") val versionName: String = "1.0.0",
    @SerialName("min_version_code") val minVersionCode: Int = 1,
    @SerialName("download_url") val downloadUrl: String = "",
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0L,
    val changelog: String = "",
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("is_mandatory") val isMandatory: Boolean = false,
    @SerialName("apk_exists") val apkExists: Boolean = false,
    /** SHA-256 (hex) của APK; rỗng nếu máy chủ không tính được — khi đó chỉ kiểm tra dung lượng. */
    val sha256: String = "",
    val platform: String = ""
) {
    val formattedFileSize: String
        get() = if (fileSizeBytes > 0) {
            val mb = fileSizeBytes.toDouble() / (1024 * 1024)
            "${((mb * 10).toLong()) / 10.0} MB"
        } else ""
}

class AppUpdateApi {
    private val client = TruyenCloudApi.httpClient
    private val base: String get() = TruyenCloudApi.baseUrl

    /**
     * Kiểm tra phiên bản mới nhất của ứng dụng cho [platform].
     * Với iOS máy chủ luôn trả "không có bản mới" (version_code 0) — client iOS không gọi hàm này.
     */
    suspend fun checkUpdate(platform: UpdatePlatform): AppVersionResponse {
        return client.get("$base/v1/app/check-update") {
            parameter("platform", platform.apiName)
        }.throwIfError().body()
    }
}
