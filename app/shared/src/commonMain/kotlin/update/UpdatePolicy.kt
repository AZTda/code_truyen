package com.truyencloud.shared.update

enum class UpdatePlatform(val apiName: String) {
    ANDROID("android"),
    IOS("ios")
}

sealed interface UpdateDecision {
    /** Nền tảng không có cập nhật tại chỗ (iOS): không hỏi máy chủ, không hiện hộp thoại, không mở URL nào. */
    data object Unsupported : UpdateDecision

    data object UpToDate : UpdateDecision

    /** [mandatory]: chỉ Android mới có thể bắt buộc — xem [UpdatePolicy.decide]. */
    data class Available(val mandatory: Boolean) : UpdateDecision
}

object UpdatePolicy {

    /** APK chỉ cài được trên Android; iOS cập nhật qua TestFlight/App Store. */
    fun supportsOta(platform: UpdatePlatform): Boolean = platform == UpdatePlatform.ANDROID

    /**
     * Quyết định hiển thị cập nhật.
     *
     * Bản bắt buộc = máy chủ đặt `is_mandatory` HOẶC bản đang chạy thấp hơn `min_version_code`.
     * iOS luôn [UpdateDecision.Unsupported], nên một bản "bắt buộc" không bao giờ khoá được tab Cài đặt trên iOS.
     */
    fun decide(
        platform: UpdatePlatform,
        currentVersionCode: Int,
        latestVersionCode: Int,
        serverSaysMandatory: Boolean,
        minVersionCode: Int = 0
    ): UpdateDecision {
        if (!supportsOta(platform)) return UpdateDecision.Unsupported
        if (latestVersionCode <= currentVersionCode) return UpdateDecision.UpToDate
        val forced = serverSaysMandatory || currentVersionCode < minVersionCode
        return UpdateDecision.Available(mandatory = forced)
    }

    /** Đọc versionCode từ chuỗi CFBundleVersion của iOS ("12", "12.3", "abc" → 1). */
    fun parseBundleVersion(raw: String?): Int {
        val major = raw?.trim()?.substringBefore('.')?.toIntOrNull()
        return if (major != null && major > 0) major else 1
    }
}
