package com.truyencloud.shared.library

import com.truyencloud.shared.repo.AccountSwitchPolicy
import com.truyencloud.shared.tts.TtsResumeStore

/**
 * Dữ liệu của màn hình chính nằm trong CÀI ĐẶT (không phải Room) nhưng thuộc về một tài khoản: bản lưu các bảng Khám phá
 * ([DiscoverCacheStore]), lịch sử tìm kiếm ([SearchHistoryStore]) và chỗ nghe dở của các chương ([TtsResumeStore]). Xoá dữ liệu người dùng khỏi máy chỉ dọn Room
 * (LocalDataMaintenance.wipeUserData), nên đường xoá phải gọi thêm [clear] — nếu không, tài khoản kế tiếp trên máy sẽ
 * thừa hưởng danh sách gợi ý và từ khoá đã tìm của người trước.
 */
object AccountScopedPrefs {

    /**
     * Xoá khỏi cài đặt mọi thứ gắn với dữ liệu của các tài khoản [accountIds] (id rỗng = khách): mọi bản lưu Khám phá
     * của từng tài khoản, bản lưu kiểu cũ không theo tài khoản, và lịch sử tìm kiếm (chỉ có một bản, của chủ nào cũng xoá).
     * Bản lưu của khách (công khai, không cá nhân) luôn được dọn cùng. [prefs] null thì không làm gì.
     */
    fun clear(prefs: HomePrefsStore?, accountIds: Collection<String>) {
        if (prefs == null) return
        val cache = DiscoverCacheStore(prefs)
        (accountIds.map(HomePrefKeys::accountSlot) + AccountSwitchPolicy.GUEST_ID).toSet().forEach(cache::clearAccount)
        cache.clearLegacy()
        SearchHistoryStore.wipe(prefs)
        TtsResumeStore.wipe(prefs)
    }
}
