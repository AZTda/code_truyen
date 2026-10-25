package com.truyencloud.compose.screens.home

import com.truyencloud.shared.library.AccountScopedPrefs
import com.truyencloud.shared.library.HomePrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Giữ dữ liệu theo tài khoản của màn hình chính (Khám phá, lịch sử và gợi ý tìm kiếm) khớp với tài khoản đang dùng mà
 * KHÔNG cần khởi động lại ứng dụng. HomeViewModel được tạo một lần ở gốc AppNav và sống qua đăng nhập / đăng xuất / đổi
 * tài khoản / xoá dữ liệu; thứ gì chỉ nạp một lần lúc khởi tạo sẽ cũ đi (bài học "Từ vựng hôm nay"), nên hai nguồn sự kiện
 * được theo dõi ở đây:
 *  - [accounts]: tài khoản (hoặc trạng thái đăng nhập) đổi → bỏ dữ liệu của tài khoản cũ, dựng lại cho tài khoản mới.
 *  - [wipes]: dữ liệu trên máy bị xoá → dọn phần nằm trong cài đặt rồi nạp lại. Đổi tài khoản và đăng xuất kèm xoá (nút
 *    "Xóa dữ liệu trên máy" trong hộp thoại đăng xuất của Cài đặt) đã được AccountSession dọn sẵn; đường này là lưới an toàn
 *    cho mọi nơi gọi thẳng LocalDataMaintenance.wipeUserData() và để làm mới bộ nhớ của màn hình chính.
 */
internal class HomeAccountSync(
    private val accounts: Flow<HomeAccount>,
    private val wipes: StateFlow<Int>,
    private val prefs: HomePrefsStore?,
    private val discover: HomeDiscoverController,
    private val suggestions: HomeSuggestionController,
    private val scope: CoroutineScope,
    /**
     * Gọi mỗi khi tài khoản đổi hoặc dữ liệu bị xoá: bộ nhớ của màn hình chính còn giữ thứ không thuộc về người dùng mới,
     * vd. lớp tìm kiếm đang mở với kết quả khớp từ tủ sách của người trước (HomeViewModel đóng lớp đó lại).
     */
    private val onAccountReset: () -> Unit = {}
) {
    fun start() {
        // Lần phát đầu là tài khoản lúc khởi tạo → switchAccount trả false, không làm gì
        scope.launch { accounts.distinctUntilChanged().collect { if (discover.switchAccount(it)) onAccountReset() } }
        var handled = wipes.value
        scope.launch {
            wipes.collect { count ->
                if (count == handled) return@collect
                handled = count
                onWiped()
            }
        }
    }

    private fun onWiped() {
        onAccountReset()
        AccountScopedPrefs.clear(prefs, listOf(discover.account.id))
        // Xoá cả lịch sử đang giữ trong RAM (khi không có kho cài đặt) rồi tính lại gợi ý
        suggestions.clearHistory()
        discover.reset()
    }
}
