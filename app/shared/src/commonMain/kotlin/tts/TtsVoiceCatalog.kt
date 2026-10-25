package com.truyencloud.shared.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Danh mục giọng AI của MỘT máy chủ: giọng thật của mô hình đang chạy, giọng mặc định theo giới tính và cách máy chủ đọc
 * mười id hồ sơ cũ ([legacyMap]).
 */
data class TtsCatalogSnapshot(
    val model: String,
    val voices: List<TtsVoiceProfile>,
    val femaleDefault: String,
    val maleDefault: String,
    val legacyMap: Map<String, String>,
    /** false = bản đi kèm app (chưa tải được từ máy chủ). */
    val fromServer: Boolean
)

/**
 * Danh mục giọng AI ĐANG DÙNG của ứng dụng. Tải ở `/v1/tts/voices` lúc chạy ([TtsVoiceCatalogRepository]) nên máy chủ đổi
 * mô hình không cần ra bản app mới; chưa tải được thì dùng bản Nano đi kèm ([VieNeuVoices]).
 *
 * Id người dùng đã lưu không bao giờ bị đổi ở đây; [heardVoice] chỉ cho biết giọng THẬT máy chủ sẽ đọc cho id đó (để đánh
 * dấu đúng hàng "Đang dùng" và hiện đúng tên).
 */
object TtsVoiceCatalog {
    private val _active = MutableStateFlow(VieNeuVoices.bundledSnapshot())
    val active: StateFlow<TtsCatalogSnapshot> = _active.asStateFlow()

    /** Nhận danh mục mới; danh mục rỗng / hỏng bị bỏ qua (giữ danh mục đang có). */
    fun update(snapshot: TtsCatalogSnapshot) {
        sanitize(snapshot)?.let { _active.value = it }
    }

    /** Về bản đi kèm (dùng trong kiểm thử). */
    fun resetToBundled() {
        _active.value = VieNeuVoices.bundledSnapshot()
    }

    /** Giọng AI hiện có để chọn (mô hình đang chạy của máy chủ). */
    val voices: List<TtsVoiceProfile> get() = _active.value.voices

    /** Giọng mặc định cho cài đặt mới: giọng nữ mặc định (Nano: Mỹ Duyên). */
    fun defaultProfile(): TtsVoiceProfile = defaultFor(male = false)

    fun defaultFor(male: Boolean): TtsVoiceProfile {
        val snap = _active.value
        val id = if (male) snap.maleDefault else snap.femaleDefault
        return snap.voices.firstOrNull { it.id == id } ?: VieNeuVoices.anyMode(id) ?: snap.voices.first()
    }

    /** Giọng VieNeu có id [id]: của danh mục đang dùng, rồi tới bản đi kèm của cả hai mô hình. */
    fun knownVoice(id: String): TtsVoiceProfile? =
        _active.value.voices.firstOrNull { it.id == id } ?: VieNeuVoices.anyMode(id)

    fun isActiveVoice(id: String): Boolean = _active.value.voices.any { it.id == id }

    /**
     * Giọng thật máy chủ đọc khi app gửi [profileId]: giọng của danh mục -> chính nó; hồ sơ cũ -> theo [TtsCatalogSnapshot.legacyMap];
     * id của mô hình kia -> giọng mặc định cùng giới tính; id lạ -> giọng nữ mặc định (như máy chủ).
     */
    fun heardVoice(profileId: String): TtsVoiceProfile {
        val snap = _active.value
        snap.voices.firstOrNull { it.id == profileId }?.let { return it }
        if (LegacyVoices.isLegacy(profileId)) {
            val mapped = snap.legacyMap[profileId]
            snap.voices.firstOrNull { it.id == mapped }?.let { return it }
            return defaultFor(LegacyVoices.isMale(profileId))
        }
        val other = VieNeuVoices.anyMode(profileId) ?: return defaultFor(male = false)
        return defaultFor(other.gender == "Nam")
    }

    private fun sanitize(s: TtsCatalogSnapshot): TtsCatalogSnapshot? {
        val voices = s.voices.filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }
        if (voices.isEmpty()) return null
        val female = s.femaleDefault.takeIf { id -> voices.any { it.id == id } }
            ?: voices.firstOrNull { it.gender == "Nữ" }?.id ?: voices.first().id
        val male = s.maleDefault.takeIf { id -> voices.any { it.id == id } }
            ?: voices.firstOrNull { it.gender == "Nam" }?.id ?: female
        val legacy = LegacyVoices.mapTo(female, male) + s.legacyMap.filter { (k, v) -> LegacyVoices.isLegacy(k) && voices.any { it.id == v } }
        return s.copy(voices = voices, femaleDefault = female, maleDefault = male, legacyMap = legacy)
    }
}
