package com.truyencloud.shared.repo

import com.truyencloud.shared.api.AuthApi
import com.truyencloud.shared.api.ServerUrlValidator
import com.truyencloud.shared.api.ServerUrls
import com.truyencloud.shared.api.SessionEvent
import com.truyencloud.shared.api.SessionState
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.SupabaseAuth
import com.truyencloud.shared.data.UserProfile
import com.truyencloud.shared.prefs.PrefKeys
import com.truyencloud.shared.prefs.SettingsStore
import com.truyencloud.shared.tts.TtsVoiceResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlin.concurrent.Volatile

private val DEFAULT_PROFILE = UserProfile(
    id = AccountSwitchPolicy.GUEST_ID,
    email = "",
    displayName = GuestProfile.NAME,
    bio = GuestProfile.BIO,
    vipTier = GuestProfile.TIER,
    avatarUrl = ServerUrls.defaultAvatar(ServerUrls.DEFAULT),
    bannerUrl = ServerUrls.defaultBanner(ServerUrls.DEFAULT),
    preferredTheme = "system",
    readerFontSize = 18,
    readerLineHeight = 1.8f,
    serverUrl = ServerUrls.DEFAULT,
    totalReadingHours = 0f,
    totalChaptersRead = 0,
    totalWordsTranslated = 0,
    readingStreakDays = 0,
    authProvider = "guest",
    isLoggedIn = false
)

class UserRepository(
    private val supabaseAuth: SupabaseAuth? = null,
    private val authApi: AuthApi = AuthApi(),
    /**
     * Kho lưu bền vững. Nếu null (ví dụ trong kiểm thử) thì hành xử như bản cũ:
     * giữ trong RAM và mất khi tắt ứng dụng.
     */
    private val store: SettingsStore? = null,
    /** Xoá dữ liệu người dùng trên máy khi đổi tài khoản hoặc khi người dùng chọn "Xóa dữ liệu trên máy". */
    private val dataWiper: LocalDataWiper? = null
) {
    // Đọc kho mã hoá đúng MỘT lần lúc khởi động (mỗi lần giải mã đi qua Keystore, trên vài máy mất cả chục ms)
    private val storedToken: String = store?.getSecret(PrefKeys.AUTH_TOKEN).orEmpty()

    private val _profile = MutableStateFlow(loadProfileFromStore())
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    /** Token phiên đã ký từ máy chủ (lưu mã hoá), dùng cho mọi lời gọi cần xác thực. */
    private val _authToken = MutableStateFlow(storedToken)
    val authToken: StateFlow<String> = _authToken.asStateFlow()

    @Volatile
    private var pendingChanges: PendingChanges? = null
    private val _accountNotice = MutableStateFlow<String?>(null)

    /**
     * Điều quan trọng vừa xảy ra với dữ liệu trên máy (vd. thay đổi chưa đồng bộ đã bị xoá khi đăng xuất / đổi tài khoản).
     * Giao diện hiện hộp thoại rồi gọi [dismissAccountNotice].
     */
    val accountNotice: StateFlow<String?> = _accountNotice.asStateFlow()

    fun dismissAccountNotice() {
        _accountNotice.value = null
    }

    /**
     * Nối các kho cần đẩy nốt trước khi đăng xuất / đổi tài khoản (xem [PendingChanges]). UserRepository không biết
     * các kho khác nên AppNav gọi hàm này lúc khởi tạo.
     */
    fun attachPendingChanges(source: PendingChanges?) {
        pendingChanges = source
    }

    /** Vòng đời phiên (đăng nhập, hết hạn, gia hạn, đổi tài khoản, lần chạy đầu). */
    private val account = AccountSession(
        authApi = authApi,
        store = store,
        dataWiper = dataWiper,
        supabaseAuth = supabaseAuth,
        readProfile = { _profile.value },
        editProfile = { transform -> update(transform) },
        readToken = { _authToken.value },
        writeToken = ::setAuthToken,
        dropRejectedToken = ::dropRejectedToken,
        pendingChanges = { pendingChanges },
        postNotice = { message -> _accountNotice.value = message }
    )

    /** ACTIVE / GUEST / EXPIRED — giao diện dựa vào đây để hiện lời nhắc "phiên hết hạn". */
    val sessionState: StateFlow<SessionState> get() = TruyenCloudApi.sessionState

    /** Phát đúng một lần mỗi khi phiên chuyển từ ACTIVE sang hết hạn. */
    val sessionEvents: SharedFlow<SessionEvent> get() = TruyenCloudApi.sessionEvents

    init {
        TruyenCloudApi.baseUrl = _profile.value.serverUrl
        TruyenCloudApi.authToken = _authToken.value
        TruyenCloudApi.onSessionExpired = { account.onSessionExpired() }
        TruyenCloudApi.onAttemptSilentRefresh = { rejected -> account.trySilentRefresh(rejected) }
        account.backfillLastUser()
    }

    /** Nạp hồ sơ từ đĩa (chủ đề, cỡ chữ, tốc độ đọc, giọng đọc, URL máy chủ). */
    private fun loadProfileFromStore(): UserProfile {
        val s = store ?: return DEFAULT_PROFILE
        val d = DEFAULT_PROFILE
        // Địa chỉ máy chủ hỏng (bản cũ, sửa tay) thì quay về mặc định thay vì để mọi lời gọi thất bại
        val server = s.getString(PrefKeys.SERVER_URL, d.serverUrl)
            .takeIf { ServerUrlValidator.parse(it) != null } ?: d.serverUrl
        // Bản cũ chỉ lưu "âm sắc" (chưa có id hồ sơ giọng): id rỗng / không còn tồn tại thì suy từ âm sắc, không rơi về giọng nữ
        val voice = TtsVoiceResolver.canonicalProfile(
            storedId = s.getString(PrefKeys.TTS_VOICE_PROFILE, ""),
            storedTone = s.getString(PrefKeys.TTS_VOICE_TONE, d.ttsVoiceTone)
        )
        return UserProfile(
            id = s.getString(PrefKeys.USER_ID, d.id),
            email = s.getString(PrefKeys.EMAIL, d.email),
            displayName = s.getString(PrefKeys.DISPLAY_NAME, d.displayName),
            bio = GuestProfile.cleanBio(s.getString(PrefKeys.BIO, d.bio)),
            vipTier = s.getString(PrefKeys.VIP_TIER, d.vipTier),
            // Ảnh mặc định suy ra từ địa chỉ máy chủ đang dùng chứ không cố định theo một tên miền
            avatarUrl = s.getString(PrefKeys.AVATAR_URL, "").ifBlank { ServerUrls.defaultAvatar(server) },
            bannerUrl = s.getString(PrefKeys.BANNER_URL, "").ifBlank { ServerUrls.defaultBanner(server) },
            preferredTheme = s.getString(PrefKeys.THEME, d.preferredTheme),
            readerFontSize = s.getInt(PrefKeys.READER_FONT_SIZE, d.readerFontSize),
            readerLineHeight = s.getFloat(PrefKeys.READER_LINE_HEIGHT, d.readerLineHeight),
            serverUrl = server,
            totalReadingHours = d.totalReadingHours,
            totalChaptersRead = d.totalChaptersRead,
            totalWordsTranslated = d.totalWordsTranslated,
            readingStreakDays = d.readingStreakDays,
            authProvider = s.getString(PrefKeys.AUTH_PROVIDER, d.authProvider),
            // A persisted profile alone is not a valid session. Older builds could
            // save a guest fallback as logged in after a network failure.
            isLoggedIn = s.getBoolean(PrefKeys.IS_LOGGED_IN, d.isLoggedIn) && storedToken.isNotBlank(),
            isAdmin = s.getBoolean(PrefKeys.IS_ADMIN, false) && storedToken.isNotBlank(),
            ttsSpeed = s.getFloat(PrefKeys.TTS_SPEED, d.ttsSpeed),
            ttsAutoScroll = s.getBoolean(PrefKeys.TTS_AUTO_SCROLL, d.ttsAutoScroll),
            ttsVoiceTone = voice.name,
            ttsVoiceProfileId = voice.id,
            ttsAutoNextChapter = s.getBoolean(PrefKeys.TTS_AUTO_NEXT, d.ttsAutoNextChapter),
            ttsBackgroundPlayback = s.getBoolean(PrefKeys.TTS_BACKGROUND, d.ttsBackgroundPlayback),
            ttsOnlineMode = s.getBoolean(PrefKeys.TTS_ONLINE, d.ttsOnlineMode),
            readerPaginated = s.getBoolean(PrefKeys.READER_PAGINATED, d.readerPaginated),
            readerPageMargin = s.getInt(PrefKeys.READER_PAGE_MARGIN, d.readerPageMargin),
            readerFontFamily = s.getString(PrefKeys.READER_FONT_FAMILY, d.readerFontFamily),
            readerBrightness = s.getFloat(ReaderPrefKeys.BRIGHTNESS, d.readerBrightness).coerceIn(0.15f, 1f),
            readerKeepScreenOn = s.getBoolean(ReaderPrefKeys.KEEP_SCREEN_ON, d.readerKeepScreenOn),
            readerVolumeKeyPaging = s.getBoolean(ReaderPrefKeys.VOLUME_KEY_PAGING, d.readerVolumeKeyPaging)
        )
    }

    /** Ghi toàn bộ hồ sơ xuống đĩa. Gọi sau mỗi lần `_profile` thay đổi. */
    private fun persist(p: UserProfile) {
        val s = store ?: return
        s.putString(PrefKeys.USER_ID, p.id)
        s.putString(PrefKeys.EMAIL, p.email)
        s.putString(PrefKeys.DISPLAY_NAME, p.displayName)
        s.putString(PrefKeys.BIO, p.bio)
        s.putString(PrefKeys.VIP_TIER, p.vipTier)
        s.putString(PrefKeys.AVATAR_URL, p.avatarUrl)
        s.putString(PrefKeys.BANNER_URL, p.bannerUrl)
        s.putString(PrefKeys.THEME, p.preferredTheme)
        s.putInt(PrefKeys.READER_FONT_SIZE, p.readerFontSize)
        s.putFloat(PrefKeys.READER_LINE_HEIGHT, p.readerLineHeight)
        s.putString(PrefKeys.SERVER_URL, p.serverUrl)
        s.putString(PrefKeys.AUTH_PROVIDER, p.authProvider)
        s.putBoolean(PrefKeys.IS_LOGGED_IN, p.isLoggedIn)
        s.putBoolean(PrefKeys.IS_ADMIN, p.isAdmin)
        s.putFloat(PrefKeys.TTS_SPEED, p.ttsSpeed)
        s.putBoolean(PrefKeys.TTS_AUTO_SCROLL, p.ttsAutoScroll)
        s.putString(PrefKeys.TTS_VOICE_TONE, p.ttsVoiceTone)
        s.putString(PrefKeys.TTS_VOICE_PROFILE, p.ttsVoiceProfileId)
        s.putBoolean(PrefKeys.TTS_AUTO_NEXT, p.ttsAutoNextChapter)
        s.putBoolean(PrefKeys.TTS_BACKGROUND, p.ttsBackgroundPlayback)
        s.putBoolean(PrefKeys.TTS_ONLINE, p.ttsOnlineMode)
        s.putBoolean(PrefKeys.READER_PAGINATED, p.readerPaginated)
        s.putInt(PrefKeys.READER_PAGE_MARGIN, p.readerPageMargin)
        s.putString(PrefKeys.READER_FONT_FAMILY, p.readerFontFamily)
        s.putFloat(ReaderPrefKeys.BRIGHTNESS, p.readerBrightness)
        s.putBoolean(ReaderPrefKeys.KEEP_SCREEN_ON, p.readerKeepScreenOn)
        s.putBoolean(ReaderPrefKeys.VOLUME_KEY_PAGING, p.readerVolumeKeyPaging)
    }


    /** Điểm ghi duy nhất: cập nhật state rồi lưu xuống đĩa. */
    private fun update(transform: (UserProfile) -> UserProfile) {
        // CAS thay vì đọc-rồi-ghi: nay hồ sơ còn bị đổi từ luồng mạng (phiên hết hạn), không chỉ luồng giao diện
        val next = _profile.updateAndGet(transform)
        persist(next)
    }

    fun setAuthToken(token: String) {
        _authToken.value = token
        TruyenCloudApi.authToken = token
        if (token.isBlank()) store?.removeSecret(PrefKeys.AUTH_TOKEN) else store?.putSecret(PrefKeys.AUTH_TOKEN, token)
    }

    /** Máy chủ từ chối token: TruyenCloudApi đã xoá token và giữ trạng thái EXPIRED, ở đây chỉ dọn bản lưu. */
    private fun dropRejectedToken() {
        _authToken.value = ""
        store?.removeSecret(PrefKeys.AUTH_TOKEN)
    }

    /**
     * Đổi địa chỉ máy chủ. Token chỉ có giá trị với máy chủ đã cấp nó, nên khi đổi sang nguồn khác phiên
     * bị ngắt (dữ liệu trên máy giữ nguyên) — token cũ không bao giờ được gửi tới địa chỉ mới gõ.
     * Ảnh đại diện/ảnh bìa mặc định đi theo địa chỉ mới.
     *
     * @return true nếu phiên đăng nhập bị ngắt vì đổi sang máy chủ khác nguồn.
     */
    fun updateServerUrl(url: String): Boolean {
        val clean = url.trim().trimEnd('/')
        val sameServer = ServerUrls.sameOrigin(_profile.value.serverUrl, clean)
        TruyenCloudApi.baseUrl = clean
        update {
            it.copy(
                serverUrl = clean,
                avatarUrl = if (ServerUrls.isDefaultAvatar(it.avatarUrl)) ServerUrls.defaultAvatar(clean) else it.avatarUrl,
                bannerUrl = if (ServerUrls.isDefaultBanner(it.bannerUrl)) ServerUrls.defaultBanner(clean) else it.bannerUrl
            )
        }
        if (sameServer) return false
        val hadSession = _authToken.value.isNotBlank()
        account.detachSession()
        return hadSession
    }

    /** Khôi phục địa chỉ mặc định (https://truyen.achatvn.com). */
    fun resetServerUrl(): Boolean = updateServerUrl(ServerUrls.DEFAULT)

    fun updatePreferences(theme: String, fontSize: Int, lineHeight: Float) {
        update {
            it.copy(
                preferredTheme = theme,
                readerFontSize = fontSize.coerceIn(12, 32),
                readerLineHeight = lineHeight.coerceIn(1.2f, 2.6f)
            )
        }
    }

    fun updateReaderPaginated(paginated: Boolean) {
        update { it.copy(readerPaginated = paginated) }
    }

    fun updateReaderPageMargin(margin: Int) {
        update { it.copy(readerPageMargin = margin.coerceIn(8, 36)) }
    }

    /** Kiểu chữ trình đọc ("serif" | "sans") — trước đây chỉ nằm trong RAM nên mỗi lần mở lại quay về mặc định. */
    fun updateReaderFontFamily(type: String) {
        update { it.copy(readerFontFamily = if (type == "sans") "sans" else "serif") }
    }

    fun updateReaderBrightness(brightness: Float) {
        update { it.copy(readerBrightness = brightness.coerceIn(0.15f, 1f)) }
    }

    fun updateReaderKeepScreenOn(enabled: Boolean) {
        update { it.copy(readerKeepScreenOn = enabled) }
    }

    fun updateReaderVolumeKeyPaging(enabled: Boolean) {
        update { it.copy(readerVolumeKeyPaging = enabled) }
    }


    /**
     * Tham số bỏ trống GIỮ NGUYÊN giá trị đang lưu (trước đây mặc định cứng đặt lại giọng về "mai_phuong"). Cặp giọng luôn được
     * chuẩn hoá: id không hợp lệ không bao giờ được lưu, và tên hiển thị luôn khớp id.
     */
    fun updateTtsPreferences(
        speed: Float,
        autoScroll: Boolean,
        voiceTone: String = _profile.value.ttsVoiceTone,
        voiceProfileId: String = _profile.value.ttsVoiceProfileId,
        autoNext: Boolean = _profile.value.ttsAutoNextChapter,
        backgroundPlayback: Boolean = _profile.value.ttsBackgroundPlayback,
        onlineMode: Boolean = _profile.value.ttsOnlineMode
    ) {
        val voice = TtsVoiceResolver.canonicalProfile(voiceProfileId, voiceTone)
        update {
            it.copy(
                ttsSpeed = speed,
                ttsAutoScroll = autoScroll,
                ttsVoiceTone = voice.name,
                ttsVoiceProfileId = voice.id,
                ttsAutoNextChapter = autoNext,
                ttsBackgroundPlayback = backgroundPlayback,
                ttsOnlineMode = onlineMode
            )
        }
    }

    fun updateProfile(displayName: String, bio: String, avatarUrl: String, bannerUrl: String) {
        update {
            it.copy(
                displayName = displayName,
                bio = bio,
                avatarUrl = avatarUrl.trim().ifBlank { ServerUrls.defaultAvatar(it.serverUrl) },
                bannerUrl = bannerUrl.trim().ifBlank { ServerUrls.defaultBanner(it.serverUrl) }
            )
        }
    }

    // ── Phiên đăng nhập (xem AccountSession) ─────────────────────────────────────

    suspend fun loginWithEmail(email: String, pass: String): Result<String> = account.loginWithEmail(email, pass)

    fun continueAsGuest() = account.continueAsGuest()

    /**
     * Đăng xuất. [wipeLocalData] = true xoá tủ sách, tiến độ, dấu trang, từ vựng... trên máy;
     * false (mặc định) giữ nguyên để khách vẫn đọc tiếp.
     * Trước khi xoá token, đẩy nốt thay đổi chưa đồng bộ (tối đa ~5 giây, xem [PendingChangesSync]); xoá dữ liệu mà còn thay
     * đổi chưa lên máy chủ thì [accountNotice] báo rõ cho người dùng.
     */
    suspend fun logout(wipeLocalData: Boolean = false) = account.logout(wipeLocalData)

    /** Gia hạn token tối đa mỗi 24 giờ; gọi khi mở ứng dụng. */
    suspend fun refreshSessionIfDue() = account.refreshSessionIfDue()

    suspend fun changePassword(oldPassword: String, newPassword: String) =
        account.changePassword(oldPassword, newPassword)

    // ── Lần chạy đầu ─────────────────────────────────────────────────────────────

    fun shouldShowWelcome(): Boolean = account.shouldShowWelcome()
    fun markWelcomeSeen() = account.markWelcomeSeen()
    fun shouldOfferLogin(): Boolean = account.shouldOfferLogin()
    fun markLoginOffered() = account.markLoginOffered()
}
