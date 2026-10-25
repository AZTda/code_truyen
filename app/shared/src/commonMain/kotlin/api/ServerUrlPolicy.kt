package com.truyencloud.shared.api

import com.truyencloud.shared.update.UpdatePlatform

/** Địa chỉ máy chủ mặc định và các URL mặc định suy ra từ địa chỉ máy chủ đang dùng. */
object ServerUrls {
    const val DEFAULT = "https://truyen.achatvn.com"
    const val AVATAR_PATH = "/static/user_avatar_v2.jpg"
    const val BANNER_PATH = "/static/user_banner_v2.jpg"

    fun defaultAvatar(baseUrl: String): String = baseUrl.trimEnd('/') + AVATAR_PATH
    fun defaultBanner(baseUrl: String): String = baseUrl.trimEnd('/') + BANNER_PATH

    /** Trống hoặc trỏ tới ảnh mặc định của BẤT KỲ máy chủ nào — tức là người dùng chưa tự chọn ảnh. */
    fun isDefaultAvatar(url: String): Boolean = url.isBlank() || url.trimEnd().endsWith(AVATAR_PATH)
    fun isDefaultBanner(url: String): Boolean = url.isBlank() || url.trimEnd().endsWith(BANNER_PATH)

    /** "/static/x.jpg" của máy chủ → URL tuyệt đối, để Coil tải được. */
    fun resolveMedia(url: String, baseUrl: String): String {
        val u = url.trim()
        return if (u.startsWith("/") && !u.startsWith("//")) baseUrl.trimEnd('/') + u else u
    }

    /** Hai địa chỉ có cùng nguồn gốc (scheme + host + port)? Khác nguồn thì token cũ không được gửi sang. */
    fun sameOrigin(a: String, b: String): Boolean {
        val pa = ServerUrlValidator.parse(a) ?: return a.trim().trimEnd('/') == b.trim().trimEnd('/')
        val pb = ServerUrlValidator.parse(b) ?: return false
        return pa.scheme == pb.scheme && pa.host == pb.host && pa.port == pb.port
    }
}

/** Nền tảng hiện tại có cho phép HTTP không mã hoá tới host này không? */
fun interface CleartextPolicy {
    fun allows(host: String): Boolean
}

object CleartextPolicies {
    /**
     * Android: network_security_config.xml chỉ mở cho localhost, 127.0.0.1 và 10.0.2.2 (emulator).
     * Bản APK phát hành là bản debug nên KHÔNG có ngoại lệ cho mạng LAN — xem tài liệu trong file XML đó.
     */
    val androidNetworkSecurityConfig = CleartextPolicy { host ->
        host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
    }

    /** iOS: NSAllowsLocalNetworking cho phép HTTP tới mạng nội bộ. */
    val iosLocalNetworking = CleartextPolicy { host -> ServerUrlValidator.isLocalOrPrivateHost(host) }

    fun forPlatform(platform: UpdatePlatform): CleartextPolicy =
        if (platform == UpdatePlatform.IOS) iosLocalNetworking else androidNetworkSecurityConfig
}

sealed interface ServerUrlCheck {
    /** [url] đã chuẩn hoá; [warning] khác null khi hợp lệ nhưng nên cân nhắc (HTTP). */
    data class Valid(val url: String, val warning: String? = null) : ServerUrlCheck
    data class Invalid(val reason: String) : ServerUrlCheck
}

data class ParsedServerUrl(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String
)

object ServerUrlValidator {

    private const val HTTP_WARNING =
        "Kết nối HTTP không mã hoá: mật khẩu và dữ liệu có thể bị nghe lén. Chỉ nên dùng trong mạng nội bộ tin cậy."

    fun check(raw: String, cleartext: CleartextPolicy): ServerUrlCheck {
        var text = raw.trim()
        if (text.isEmpty()) return ServerUrlCheck.Invalid("Vui lòng nhập địa chỉ máy chủ.")
        if (text.any { it.isWhitespace() || it.code < 32 }) {
            return ServerUrlCheck.Invalid("Địa chỉ máy chủ không được chứa khoảng trắng.")
        }
        if ("://" !in text) text = "https://$text"

        val scheme = text.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") {
            return ServerUrlCheck.Invalid("Chỉ hỗ trợ địa chỉ bắt đầu bằng http:// hoặc https://.")
        }
        val afterScheme = text.substringAfter("://")
        if ('?' in afterScheme || '#' in afterScheme) {
            return ServerUrlCheck.Invalid("Địa chỉ máy chủ không được chứa tham số (?) hoặc mảnh (#).")
        }
        val authority = afterScheme.substringBefore('/')
        if ('@' in authority) {
            return ServerUrlCheck.Invalid("Địa chỉ máy chủ không được chứa tên đăng nhập hoặc mật khẩu.")
        }
        val rawPath = afterScheme.removePrefix(authority).trimEnd('/')
        if (rawPath.split('/').any { it == ".." || it == "." }) {
            return ServerUrlCheck.Invalid("Đường dẫn trong địa chỉ máy chủ không hợp lệ.")
        }

        val parsed = parse("$scheme://$authority$rawPath")
            ?: return ServerUrlCheck.Invalid("Địa chỉ máy chủ không hợp lệ. Ví dụ: https://truyen.achatvn.com")

        val normalized = buildString {
            append(parsed.scheme).append("://")
            append(if (':' in parsed.host) "[${parsed.host}]" else parsed.host)
            if (authority.endsWith(":${parsed.port}")) append(':').append(parsed.port)
            append(parsed.path)
        }

        if (parsed.scheme == "https") return ServerUrlCheck.Valid(normalized)

        return when {
            cleartext.allows(parsed.host) -> ServerUrlCheck.Valid(normalized, HTTP_WARNING)
            isLocalOrPrivateHost(parsed.host) -> ServerUrlCheck.Invalid(
                "Thiết bị này không cho phép HTTP tới địa chỉ mạng LAN (Android chỉ mở cho localhost và 10.0.2.2). " +
                    "Hãy dùng địa chỉ HTTPS."
            )
            else -> ServerUrlCheck.Invalid(
                "Địa chỉ công khai phải dùng HTTPS. HTTP chỉ dành cho mạng nội bộ."
            )
        }
    }

    /** Tách scheme/host/port/path; trả null nếu không phải URL http(s) hợp lệ. */
    fun parse(url: String): ParsedServerUrl? {
        val text = url.trim()
        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = text.substringAfter("://")
        val authority = rest.substringBefore('/')
        val path = rest.removePrefix(authority).trimEnd('/')
        if (authority.isEmpty() || '@' in authority) return null

        val host: String
        var portText: String? = null
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(1, close).lowercase()
            val tail = authority.substring(close + 1)
            if (tail.isNotEmpty()) {
                if (!tail.startsWith(":")) return null
                portText = tail.substring(1)
            }
            if (host.isEmpty() || !host.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }) return null
        } else {
            host = authority.substringBefore(':').lowercase()
            if (':' in authority) portText = authority.substringAfter(':')
            if (!isValidHostName(host)) return null
        }

        val port = if (portText == null) {
            if (scheme == "https") 443 else 80
        } else {
            portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
        return ParsedServerUrl(scheme, host, port, path)
    }

    private fun isValidHostName(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.startsWith(".") || host.endsWith(".") || ".." in host) return false
        return host.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' } &&
            host.split('.').none { it.startsWith("-") || it.endsWith("-") }
    }

    /** localhost, mạng LAN riêng, link-local, tên miền .local hoặc tên máy một nhãn (intranet). */
    fun isLocalOrPrivateHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        if (':' in h) return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
        val octets = h.split('.')
        if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
            val a = octets[0].toInt()
            val b = octets[1].toInt()
            return a == 10 || a == 127 || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }
        return '.' !in h && h.isNotEmpty()
    }
}
