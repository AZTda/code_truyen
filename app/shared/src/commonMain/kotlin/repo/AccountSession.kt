package com.truyencloud.shared.repo

import com.truyencloud.shared.api.AuthApi
import com.truyencloud.shared.api.AuthRemoteUser
import com.truyencloud.shared.api.ServerUrls
import com.truyencloud.shared.api.SessionRefreshPolicy
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.api.UserFacingException
import com.truyencloud.shared.data.SupabaseAuth
import com.truyencloud.shared.data.UserProfile
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.library.AccountScopedPrefs
import com.truyencloud.shared.library.HomePrefsStore
import com.truyencloud.shared.library.SettingsHomePrefs
import com.truyencloud.shared.prefs.PrefKeys
import com.truyencloud.shared.prefs.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Chuỗi hiển thị cho chế độ khách — một chỗ duy nhất để hồ sơ mặc định và hồ sơ sau đăng xuất luôn giống nhau. */
internal object GuestProfile {
    const val NAME = "Độc Giả Khách"
    const val BIO = "Nhấn đăng nhập để đồng bộ tủ sách giữa các thiết bị"
    const val TIER = "Khách"

    /** Bản cũ lưu câu giới thiệu khách kèm biểu tượng cảm xúc: đọc lại thì cắt phần thừa để máy đã dùng cũng thấy câu mới. */
    fun cleanBio(stored: String): String = if (stored.startsWith(BIO)) BIO else stored

    fun applyTo(profile: UserProfile, baseUrl: String): UserProfile = profile.copy(
        id = AccountSwitchPolicy.GUEST_ID,
        email = "",
        displayName = NAME,
        bio = BIO,
        vipTier = TIER,
        avatarUrl = ServerUrls.defaultAvatar(baseUrl),
        bannerUrl = ServerUrls.defaultBanner(baseUrl),
        authProvider = "guest",
        isLoggedIn = false,
        isAdmin = false
    )
}

/**
 * Toàn bộ vòng đời phiên: đăng nhập, đăng xuất, hết hạn, gia hạn, đổi tài khoản, lần chạy đầu.
 * Tách khỏi [UserRepository] để repository chỉ còn giữ hồ sơ và cài đặt.
 */
internal class AccountSession(
    private val authApi: AuthApi,
    private val store: SettingsStore?,
    private val dataWiper: LocalDataWiper?,
    private val supabaseAuth: SupabaseAuth?,
    private val readProfile: () -> UserProfile,
    private val editProfile: ((UserProfile) -> UserProfile) -> Unit,
    private val readToken: () -> String,
    private val writeToken: (String) -> Unit,
    /** Dọn token ở UserRepository khi máy chủ từ chối — TruyenCloudApi đã tự xoá phía mình. */
    private val dropRejectedToken: () -> Unit,
    /** Các kho cần đẩy nốt trước khi rời tài khoản; null (chưa nối, kiểm thử) thì bỏ qua bước đẩy. */
    private val pendingChanges: () -> PendingChanges? = { null },
    /** Báo người dùng một điều quan trọng đã xảy ra với dữ liệu của họ (AppNav hiện hộp thoại). */
    private val postNotice: (String) -> Unit = {},
    /** Id tài khoản sở hữu dữ liệu trên máy (LAST_USER_ID); "" nếu chưa có. */
    private val lastUserId: () -> String = { store?.getString(PrefKeys.LAST_USER_ID, "").orEmpty() },
    /** Dữ liệu nằm trong cài đặt nhưng thuộc về tài khoản (bản lưu Khám phá, lịch sử tìm): xoá cùng lúc với dữ liệu. */
    private val accountPrefs: HomePrefsStore? = store?.let { SettingsHomePrefs(it) }
) {

    suspend fun loginWithEmail(email: String, pass: String): Result<String> {
        return try {
            supabaseAuth?.signInWithEmail(email, pass)
            val res = authApi.login(email, pass)
            val user = res.user
            if (res.token.isBlank() || user == null) {
                throw UserFacingException("Máy chủ không trả về phiên đăng nhập hợp lệ.")
            }

            // Đổi tài khoản: xoá dữ liệu người trước TRƯỚC khi đặt token, tức là trước mọi lần đồng bộ
            wipeIfAccountSwitched(user.id)

            writeToken(res.token)
            applyRemoteUser(user, "email")
            store?.putString(PrefKeys.LAST_USER_ID, user.id)
            store?.putLong(PrefKeys.LAST_TOKEN_REFRESH_AT, getCurrentEpochMillis())
            SyncStatus.reset()
            Result.success(res.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Tài khoản KHÁC đăng nhập: xoá dữ liệu người trước trước lần đồng bộ đầu tiên. Thường phiên cũ đã hết token (đăng xuất /
     * hết hạn) nên không đẩy được gì; còn token thì đẩy nốt trước khi xoá. Còn sót thì báo người dùng (xoá vẫn diễn ra).
     */
    internal suspend fun wipeIfAccountSwitched(newUserId: String) {
        if (AccountSwitchPolicy.onLogin(lastUserId(), newUserId) != AccountSwitchAction.WIPE_BEFORE_SYNC) return
        val unsynced = flushPendingBestEffort()
        wipeLocalDataOrFail(newUserId)
        if (unsynced > 0) postNotice(AccountNotices.lostOnSwitch(unsynced))
    }

    fun continueAsGuest() {
        writeToken("")
        editProfile { GuestProfile.applyTo(it, TruyenCloudApi.baseUrl) }
        // Chọn chế độ khách sau khi phiên hết hạn: dòng đồng bộ phải về "Chưa đăng nhập" chứ không còn báo "hết hạn"
        SyncStatus.reset()
    }

    /**
     * Đăng xuất. [wipeLocalData] = false giữ tủ sách/từ vựng trên máy (mặc định, và là thứ khách tiếp tục dùng);
     * true xoá sạch dữ liệu người dùng và quên luôn id tài khoản.
     */
    suspend fun logout(wipeLocalData: Boolean) {
        // Đẩy nốt khi token còn hiệu lực: sau writeToken("") không đẩy được nữa, và xoá dữ liệu sẽ mất luôn phần chưa lên máy chủ.
        val unsynced = flushPendingBestEffort()
        try {
            supabaseAuth?.signOut()
        } catch (_: Exception) {
        }
        writeToken("")
        if (wipeLocalData) {
            try {
                wipeLocalDataOrFail()
                store?.remove(PrefKeys.LAST_USER_ID)
                if (unsynced > 0) postNotice(AccountNotices.lostOnLogout(unsynced))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Đã đăng xuất rồi; dữ liệu còn nguyên và người dùng có thể xoá lại trong Quản lý bộ nhớ
            }
        }
        store?.remove(PrefKeys.LAST_TOKEN_REFRESH_AT)
        editProfile { GuestProfile.applyTo(it, TruyenCloudApi.baseUrl) }
        SyncStatus.reset()
    }

    /** Đổi máy chủ: token cũ chỉ có giá trị với máy chủ cũ, không được gửi sang địa chỉ mới. Giữ dữ liệu. */
    fun detachSession() {
        if (readToken().isBlank() && !readProfile().isLoggedIn) {
            // Khách, hoặc phiên đã hết hạn ở máy chủ cũ: dấu "hết hạn" chỉ có nghĩa với máy chủ đó nên phải bỏ đi.
            // Hồ sơ khách tự chỉnh (tên, ảnh) được giữ nguyên.
            writeToken("")
            SyncStatus.reset()
            return
        }
        writeToken("")
        store?.remove(PrefKeys.LAST_TOKEN_REFRESH_AT)
        editProfile { GuestProfile.applyTo(it, TruyenCloudApi.baseUrl) }
        SyncStatus.reset()
    }

    /** TruyenCloudApi báo máy chủ từ chối token (401): về chế độ khách nhưng GIỮ dữ liệu và id tài khoản cũ. */
    fun onSessionExpired() {
        dropRejectedToken()
        store?.remove(PrefKeys.LAST_TOKEN_REFRESH_AT)
        editProfile { GuestProfile.applyTo(it, TruyenCloudApi.baseUrl) }
    }

    private val refreshMutex = Mutex()

    /** Tự động gia hạn token ngầm khi một lời gọi API gặp 401 (Silent Token Refresh). */
    suspend fun trySilentRefresh(rejectedToken: String): Boolean = refreshMutex.withLock {
        val current = readToken()
        if (current.isBlank()) return@withLock false
        if (current != rejectedToken) return@withLock true
        return@withLock try {
            val res = authApi.refresh()
            if (res.token.isNotBlank() && readToken() == rejectedToken) {
                writeToken(res.token)
                store?.putLong(PrefKeys.LAST_TOKEN_REFRESH_AT, getCurrentEpochMillis())
                res.user?.let(::applyServerFlags)
                SyncStatus.reportSuccess()
                true
            } else false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Gia hạn token tối đa mỗi 24 giờ. Lỗi mạng bỏ qua; 401 do TruyenCloudApi xử lý (phiên hết hạn). */
    suspend fun refreshSessionIfDue() {
        val token = readToken()
        val now = getCurrentEpochMillis()
        val last = store?.getLong(PrefKeys.LAST_TOKEN_REFRESH_AT, 0L) ?: 0L
        if (token.isBlank()) return
        if (!SessionRefreshPolicy.isDue(true, now, last)) {
            // Chưa đến hạn gia hạn: chỉ hỏi /auth/me để cập nhật quyền quản trị (và phát hiện tài khoản đã bị xoá)
            try {
                authApi.me().let(::applyServerFlags)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            return
        }
        try {
            val res = authApi.refresh()
            // Người dùng đăng xuất hoặc đăng nhập lại trong lúc chờ: không ghi đè phiên mới bằng token cũ
            if (res.token.isBlank() || readToken() != token) return
            writeToken(res.token)
            store?.putLong(PrefKeys.LAST_TOKEN_REFRESH_AT, getCurrentEpochMillis())
            res.user?.let(::applyServerFlags)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 401: TruyenCloudApi đã chuyển sang trạng thái hết hạn. Mất mạng hay máy chủ lỗi: im lặng,
            // phiên hiện tại vẫn dùng được và lần mở sau sẽ thử lại.
        }
    }

    suspend fun changePassword(oldPassword: String, newPassword: String) {
        authApi.changePassword(oldPassword, newPassword)
    }

    /**
     * Bản cũ chưa ghi nhớ id tài khoản. Lấy id từ hồ sơ đã lưu (USER_ID) để khi một tài khoản KHÁC đăng nhập, dữ liệu của
     * người trước vẫn bị phát hiện và xoá trước lần đồng bộ đầu tiên. Không đòi token hay cờ đăng nhập: token có thể
     * không giải mã được / đã hết hạn, hoặc máy đã đăng xuất ở bản cũ (giữ dữ liệu mà không ghi chủ) — mà hồ sơ vẫn
     * ghi đúng chủ; bỏ sót thì tài khoản sau nhận dữ liệu người trước và đẩy nó lên tài khoản mới.
     */
    fun backfillLastUser() {
        val s = store ?: return
        val owner = AccountSwitchPolicy.ownerToBackfill(
            lastUserId = s.getString(PrefKeys.LAST_USER_ID, ""),
            persistedProfileId = s.getString(PrefKeys.USER_ID, "")
        ) ?: return
        s.putString(PrefKeys.LAST_USER_ID, owner)
    }

    // ── Lần chạy đầu ─────────────────────────────────────────────────────────────

    fun shouldShowWelcome(): Boolean =
        store != null && !store.getBoolean(PrefKeys.WELCOME_SEEN, false) && !readProfile().isLoggedIn

    fun markWelcomeSeen() {
        store?.putBoolean(PrefKeys.WELCOME_SEEN, true)
    }

    fun shouldOfferLogin(): Boolean =
        store != null && !store.getBoolean(PrefKeys.LOGIN_OFFER_SHOWN, false) && !readProfile().isLoggedIn

    fun markLoginOffered() {
        store?.putBoolean(PrefKeys.LOGIN_OFFER_SHOWN, true)
    }

    // ── Nội bộ ───────────────────────────────────────────────────────────────────

    /**
     * Cố đẩy nốt thay đổi chưa đồng bộ (tối đa ~5 giây, không bao giờ ném lỗi) và trả về số mục CÒN LẠI. Không có token thì
     * không đẩy được, chỉ đếm — để việc xoá sau đó biết có phải báo người dùng không.
     */
    private suspend fun flushPendingBestEffort(): Int {
        val pending = pendingChanges() ?: return 0
        return try {
            if (readToken().isNotBlank()) pending.flushBounded() else pending.pendingCount()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
    }

    /** [alsoOwners]: tài khoản sắp vào (nếu có) — bản lưu cũ của nó cũng không còn khớp với dữ liệu vừa xoá. */
    private suspend fun wipeLocalDataOrFail(vararg alsoOwners: String) {
        val wiper = dataWiper ?: return
        // Chủ của dữ liệu sắp xoá: đọc trước vì đăng xuất xoá LAST_USER_ID ngay sau đó
        val owners = listOf(lastUserId(), readProfile().id) + alsoOwners
        try {
            wiper.wipeUserData()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw UserFacingException(
                "Không xoá được dữ liệu trên máy nên chưa thể tiếp tục. Vui lòng thử lại."
            )
        }
        // Room đã sạch; phần nằm trong cài đặt (bản lưu Khám phá, lịch sử tìm) cũng phải đi theo
        AccountScopedPrefs.clear(accountPrefs, owners)
    }

    private fun applyRemoteUser(remote: AuthRemoteUser, provider: String) {
        val base = TruyenCloudApi.baseUrl
        editProfile {
            it.copy(
                id = remote.id,
                email = remote.email,
                displayName = remote.display_name.ifBlank { remote.email.substringBefore('@').ifBlank { "Độc giả" } },
                bio = remote.bio,
                vipTier = remote.vip_tier,
                avatarUrl = ServerUrls.resolveMedia(remote.avatar_url, base).ifBlank { ServerUrls.defaultAvatar(base) },
                bannerUrl = ServerUrls.resolveMedia(remote.banner_url, base).ifBlank { ServerUrls.defaultBanner(base) },
                // Các con số thống kê không hardcode: tính từ dữ liệu thật ở ReaderRepository / VocabularyRepository
                authProvider = provider,
                isLoggedIn = true,
                isAdmin = remote.is_admin
            )
        }
    }

    /** Khi gia hạn chỉ cập nhật phần do máy chủ quyết định; tên/bio/ảnh người dùng tự sửa trên máy được giữ. */
    private fun applyServerFlags(remote: AuthRemoteUser) {
        editProfile {
            it.copy(
                isAdmin = remote.is_admin,
                vipTier = remote.vip_tier.ifBlank { it.vipTier },
                email = remote.email.ifBlank { it.email }
            )
        }
    }
}
