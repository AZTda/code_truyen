package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.tts.TtsVoiceProfile
import com.truyencloud.shared.tts.TtsVoiceResolver

/**
 * Giọng đọc: ô chọn nguồn (giọng AI từ máy chủ / giọng có sẵn trên máy). Giọng AI hiện hàng chip lọc theo giới tính / miền và
 * danh sách giọng thật (chạm một hàng là chọn đúng id của hàng đó); giọng máy hiện thẻ giải thích và ba âm sắc. Cách chọn và nhãn
 * nằm ở [TtsVoiceChoices].
 */
@Composable
fun AudiobookVoiceCatalogSection(
    isOnlineMode: Boolean,
    onToggleOnlineMode: (Boolean) -> Unit,
    voiceProfileId: String,
    voiceProfiles: List<TtsVoiceProfile>,
    onSelectVoiceProfile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReaderSettingBlock(
            label = "Giọng đọc",
            trailing = if (isOnlineMode) "${TtsVoiceChoices.aiVoices(voiceProfiles).size} giọng" else null,
            modifier = Modifier.padding(horizontal = ReaderSheetInset)
        ) {
            SegmentedControl(
                options = listOf("Giọng AI", "Giọng máy"),
                selectedIndex = if (isOnlineMode) 0 else 1,
                onSelect = { onToggleOnlineMode(it == 0) }
            )
        }
        Text(
            text = if (isOnlineMode) {
                "Giọng AI do MiniPC của bạn tạo sẵn, cần kết nối mạng; máy chủ lỗi thì tự đọc bằng giọng của điện thoại và báo cho bạn."
            } else {
                "Giọng có sẵn trên máy, đọc được cả khi không có mạng."
            },
            style = AppText.secondary,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = ReaderSheetInset + 16.dp)
        )
        if (isOnlineMode) {
            OnlineVoiceList(voiceProfiles, voiceProfileId, onSelectVoiceProfile)
        } else {
            OfflineVoiceBlock(voiceProfileId, onSelectVoiceProfile)
        }
    }
}

@Composable
private fun OnlineVoiceList(
    voiceProfiles: List<TtsVoiceProfile>,
    voiceProfileId: String,
    onSelectVoiceProfile: (String) -> Unit
) {
    val colors = LocalCustomColors.current
    val categories = remember(voiceProfiles) { TtsVoiceChoices.categories(voiceProfiles) }
    var category by remember { mutableStateOf(categories.first()) }
    val filtered = remember(category, voiceProfiles) { TtsVoiceChoices.filter(voiceProfiles, category) }
    // Giọng máy chủ THẬT SỰ đọc cho id đang dùng (hồ sơ cũ -> giọng mặc định cùng giới tính)
    val activeId = TtsVoiceChoices.heard(voiceProfileId).id

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ReaderSheetInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { name ->
            FilterChip8(label = name, selected = category == name, onClick = { category = name })
        }
    }
    if (filtered.isEmpty()) {
        Text(
            text = "Chưa có giọng nào trong nhóm này",
            style = AppText.secondary,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
        )
        return
    }
    InsetGroup(footer = TtsVoiceChoices.AI_VOICE_NOTE) {
        filtered.forEach { profile ->
            val isSelected = profile.id == activeId
            InsetRow(
                title = profile.name,
                subtitle = TtsVoiceChoices.subtitle(profile),
                modifier = Modifier.semantics { selected = isSelected },
                onClick = { onSelectVoiceProfile(profile.id) },
                trailing = {
                    if (isSelected) {
                        Icon(
                            imageVector = TruyenCloudIcons.Check,
                            contentDescription = "Đang dùng",
                            tint = colors.accent,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfflineVoiceBlock(
    voiceProfileId: String,
    onSelectVoiceProfile: (String) -> Unit
) {
    val active = TtsVoiceChoices.active(voiceProfileId)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        InsetGroup {
            InsetRow(
                title = "Đang đọc bằng giọng của máy",
                subtitle = "Tổng hợp tiếng nói ngay trên máy, không tốn dữ liệu mạng",
                icon = TruyenCloudIcons.Smartphone,
                iconTint = InsetIconTint.Success
            )
        }
        ReaderSettingBlock(label = "Âm sắc giọng máy", modifier = Modifier.padding(horizontal = ReaderSheetInset)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TtsVoiceResolver.OFFLINE_TONES.forEach { tone ->
                    FilterChip8(
                        label = tone,
                        selected = TtsVoiceChoices.isToneSelected(tone, active),
                        onClick = { onSelectVoiceProfile(TtsVoiceChoices.profileIdForTone(tone, active)) }
                    )
                }
            }
        }
    }
}
