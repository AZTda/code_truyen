package com.truyencloud.compose.navigation

import androidx.compose.runtime.*
import com.truyencloud.compose.screens.auth.LoginOfferDialog
import com.truyencloud.compose.screens.auth.SessionExpiredDialog
import com.truyencloud.compose.screens.home.HomeViewModel
import com.truyencloud.compose.screens.settings.AccountNoticeDialog
import com.truyencloud.shared.api.SessionState
import com.truyencloud.shared.repo.BookmarkRepository
import com.truyencloud.shared.repo.ReaderRepository
import com.truyencloud.shared.repo.SyncNow
import com.truyencloud.shared.repo.SyncStatus
import com.truyencloud.shared.repo.UserRepository
import com.truyencloud.shared.repo.VocabularyRepository

/**
 * Mọi thứ liên quan tới phiên đăng nhập mà AppNav phải hiển thị hoặc kích hoạt:
 *
 * 1. Mở app: gia hạn token tối đa mỗi 24 giờ (lỗi mạng bị bỏ qua).
 * 2. Phiên hết hạn (máy chủ trả 401 cho token của mình): hộp thoại "Phiên đăng nhập đã hết hạn — đăng nhập lại"
 *    có thể đóng, dẫn tới màn hình đăng nhập. Dữ liệu trên máy được giữ nguyên.
 * 3. Lần chạy đầu: hiện hướng dẫn chào mừng MỘT lần, rồi mời đăng nhập MỘT lần.
 * 4. Đăng xuất / đổi tài khoản buộc phải bỏ lại thay đổi chưa đồng bộ: nói rõ cho người dùng biết.
 *
 * @param allowExpiryPrompt false khi đang ở màn hình đọc hoặc màn đăng nhập: không chen ngang người đang đọc,
 *   lời nhắc sẽ hiện khi họ thoát ra.
 * @param onNavigateToAuth mở màn hình đăng nhập.
 */
@Composable
internal fun SessionPrompts(
    userRepository: UserRepository,
    homeViewModel: HomeViewModel,
    allowExpiryPrompt: Boolean,
    onNavigateToAuth: () -> Unit
) {
    // ── 1. Gia hạn token ─────────────────────────────────────────────────────────
    LaunchedEffect(Unit) { userRepository.refreshSessionIfDue() }

    // ── 2. Phiên hết hạn ─────────────────────────────────────────────────────────
    val sessionState by userRepository.sessionState.collectAsState()
    var expiryDismissed by remember { mutableStateOf(false) }
    // Hết hạn lần nữa sau khi đã đăng nhập lại thì phải nhắc lại
    LaunchedEffect(Unit) { userRepository.sessionEvents.collect { expiryDismissed = false } }
    if (allowExpiryPrompt && sessionState == SessionState.EXPIRED && !expiryDismissed) {
        SessionExpiredDialog(
            onLogin = {
                expiryDismissed = true
                onNavigateToAuth()
            },
            onDismiss = { expiryDismissed = true }
        )
    }

    // ── 3. Lần chạy đầu ──────────────────────────────────────────────────────────
    // Phải quan sát đủ hai bước "hướng dẫn HIỆN" rồi "hướng dẫn ĐÓNG". Chỉ dựa vào cờ "đã yêu cầu mở" là sai: các
    // LaunchedEffect chạy liền nhau trong cùng một khung hình, nên effect theo dõi trạng thái đóng/mở còn đọc
    // giá trị cũ (false) và tưởng hướng dẫn đã đóng ngay khi vừa được yêu cầu mở.
    val homeState by homeViewModel.uiState.collectAsState()
    var welcomePhase by remember { mutableStateOf(WelcomePhase.IDLE) }
    var showLoginOffer by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (userRepository.shouldShowWelcome()) {
            welcomePhase = WelcomePhase.REQUESTED
            homeViewModel.openWelcomeGuide()
        }
    }
    LaunchedEffect(homeState.showWelcomeGuide) {
        val visible = homeState.showWelcomeGuide
        if (visible && welcomePhase == WelcomePhase.REQUESTED) {
            welcomePhase = WelcomePhase.SHOWING
        } else if (!visible && welcomePhase == WelcomePhase.SHOWING) {
            welcomePhase = WelcomePhase.DONE
            userRepository.markWelcomeSeen()
            if (userRepository.shouldOfferLogin()) {
                userRepository.markLoginOffered()
                showLoginOffer = true
            }
        }
    }
    if (showLoginOffer) {
        LoginOfferDialog(
            onLogin = {
                showLoginOffer = false
                onNavigateToAuth()
            },
            onDismiss = { showLoginOffer = false }
        )
    }

    // ── 4. Thay đổi chưa đồng bộ bị bỏ lại ───────────────────────────────────────
    val accountNotice by userRepository.accountNotice.collectAsState()
    accountNotice?.let { message ->
        AccountNoticeDialog(message = message, onDismiss = userRepository::dismissAccountNotice)
    }
}

/**
 * "Đồng bộ ngay" trong Cài đặt: đẩy tiến độ đang chờ, kéo tiến độ về, đồng bộ dấu trang và từ vựng. Chạy đủ cả ba rồi ném
 * lỗi ĐẦU TIÊN (đã thân thiện hoá ở tầng giao diện) để dòng "Đồng bộ đám mây" hiện lỗi thật thay vì im lặng; chỉ báo xanh
 * khi mọi thứ lên máy chủ hết (luật ở [SyncNow]).
 */
internal fun syncNowAction(
    readerRepository: ReaderRepository,
    bookmarkRepository: BookmarkRepository?,
    vocabularyRepository: VocabularyRepository? = null
): suspend () -> Unit = {
    SyncNow.runAll(
        {
            val outcome = readerRepository.syncAllProgress()
            SyncNow.requireProgressComplete(outcome, readerRepository.pendingProgressCount())
        },
        { bookmarkRepository?.syncWithServer(force = true)?.getOrThrow() },
        { SyncNow.requireVocabularySynced(vocabularyRepository?.sync()) }
    )
    SyncStatus.reportSuccess()
}

private enum class WelcomePhase { IDLE, REQUESTED, SHOWING, DONE }
