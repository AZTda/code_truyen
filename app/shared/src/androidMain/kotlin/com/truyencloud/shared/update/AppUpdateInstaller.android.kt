package com.truyencloud.shared.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.truyencloud.shared.api.ErrorContext
import com.truyencloud.shared.api.FriendlyErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

actual object AppUpdateInstaller {
    private const val MAX_REDIRECTS = 5
    private const val UPDATE_FILE_NAME = "truyencloud_update.apk"

    private var appContext: Context? = null

    /**
     * Khởi tạo context — gọi trong MainActivity.onCreate hoặc Application.onCreate.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    actual val platform: UpdatePlatform = UpdatePlatform.ANDROID

    private fun packageInfo(ctx: Context): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        }

    actual fun getCurrentVersionCode(): Int {
        val ctx = appContext ?: return 1
        return try {
            val info = packageInfo(ctx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        } catch (_: Exception) {
            1
        }
    }

    actual fun getCurrentVersionName(): String {
        val ctx = appContext ?: return "1.0.0"
        return try {
            packageInfo(ctx).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    actual suspend fun downloadAndInstallApk(
        request: ApkDownloadRequest,
        onProgress: (Float) -> Unit,
        onError: (String) -> Unit
    ) {
        val ctx = appContext
        if (ctx == null) {
            onError("Context ứng dụng chưa được khởi tạo")
            return
        }
        val firstUrl = UpdateUrlPolicy.resolve(request.baseUrl, request.downloadUrl)
        if (firstUrl == null) {
            onError("Đường dẫn cập nhật không thuộc máy chủ của bạn nên bị từ chối vì lý do an toàn.")
            return
        }

        val updatesDir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, UPDATE_FILE_NAME)
        if (apkFile.exists()) apkFile.delete()

        // Kết quả: null = đã sẵn sàng cài; khác null = thông báo lỗi
        val failure: String? = withContext(Dispatchers.IO) {
            try {
                download(request, firstUrl, apkFile) { progress ->
                    withContext(Dispatchers.Main) { onProgress(progress) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    apkFile.delete()
                    throw e
                }
                "Lỗi khi tải tệp cập nhật: ${FriendlyErrors.of(e, ErrorContext.UPDATE_CHECK)}"
            }
        }

        if (failure != null) {
            apkFile.delete()
            withContext(Dispatchers.Main) { onError(failure) }
            return
        }

        withContext(Dispatchers.Main) {
            onProgress(1.0f)
            try {
                // Lấy Uri an toàn qua FileProvider
                val apkUri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(installIntent)
            } catch (e: Exception) {
                onError("Không mở được trình cài đặt của hệ thống. Vui lòng thử lại; nếu vẫn lỗi, hãy cài bản mới thủ công.")
            }
        }
    }

    /** Tải, băm SHA-256 ngay trong lúc tải, đối chiếu manifest. Trả null nếu hợp lệ, ngược lại là thông báo lỗi. */
    private suspend fun kotlinx.coroutines.CoroutineScope.download(
        request: ApkDownloadRequest,
        firstUrl: String,
        target: File,
        report: suspend (Float) -> Unit
    ): String? {
        var url = firstUrl
        var hops = 0
        while (true) {
            ensureActive()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                // Tự đi theo chuyển hướng để kiểm tra từng chặng: HttpURLConnection sẽ theo sang host lạ
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "TruyenCloud-Android/${getCurrentVersionName()}")
                // Không để máy chủ/CDN nén giữa đường: dung lượng tải về phải đúng bằng file_size_bytes trong manifest
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                connection.connect()
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank() || ++hops > MAX_REDIRECTS) return "Máy chủ chuyển hướng quá nhiều lần."
                    val next = UpdateUrlPolicy.resolve(request.baseUrl, URL(URL(url), location).toString())
                        ?: return "Máy chủ chuyển hướng tới địa chỉ lạ nên bị từ chối vì lý do an toàn."
                    url = next
                    continue
                }
                if (code !in 200..299) return "Máy chủ phản hồi mã lỗi: $code"
                return saveAndVerify(request, connection, target, report)
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.saveAndVerify(
        request: ApkDownloadRequest,
        connection: HttpURLConnection,
        target: File,
        report: suspend (Float) -> Unit
    ): String? {
        val totalBytes = connection.contentLengthLong
        val hash = Sha256()
        var downloaded = 0L
        var lastReport = System.currentTimeMillis()

        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    hash.update(buffer, 0, read)
                    downloaded += read

                    val now = System.currentTimeMillis()
                    if (totalBytes > 0 && (now - lastReport > 80 || downloaded == totalBytes)) {
                        lastReport = now
                        report((downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
        }

        if (totalBytes > 0 && downloaded != totalBytes) {
            return "Tải chưa đủ tệp cập nhật ($downloaded/$totalBytes byte). Vui lòng thử lại."
        }
        val check = ApkIntegrity.verify(
            expectedSha256 = request.expectedSha256,
            expectedSizeBytes = request.expectedSizeBytes,
            actualSha256 = hash.digestHex(),
            actualSizeBytes = downloaded
        )
        return ApkIntegrity.messageFor(check)
    }
}
