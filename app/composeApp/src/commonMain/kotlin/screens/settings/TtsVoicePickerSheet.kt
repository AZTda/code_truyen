package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.truyencloud.compose.components.InsetGroupFooter
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.screens.reader.TtsVoiceChoices
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.tts.TtsVoiceProfile

/**
 * Chọn giọng đọc AI: hàng chip lọc theo giới tính / miền và danh sách giọng thật (tên, giới tính · miền · phong cách). Chọn
 * một giọng là áp dụng đúng id của hàng đó ngay và đóng tờ trượt; nghe thử bằng hàng "Nghe thử" ở Cài đặt. Cách chọn và nhãn
 * nằm ở [TtsVoiceChoices] (cùng tờ cài đặt nghe đọc của màn đọc).
 */
@Composable
fun TtsVoicePickerSheet(
    voiceProfiles: List<TtsVoiceProfile>,
    ttsVoiceProfileId: String,
    ttsVoiceTone: String,
    onVoiceProfileChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalCustomColors.current
    val categories = remember(voiceProfiles) { TtsVoiceChoices.categories(voiceProfiles) }
    var category by remember { mutableStateOf(categories.first()) }
    val filtered = remember(category, voiceProfiles) { TtsVoiceChoices.filter(voiceProfiles, category) }
    // Giọng máy chủ THẬT SỰ đọc cho id đang dùng (hồ sơ cũ -> giọng mặc định cùng giới tính; id hỏng thì suy từ âm sắc)
    val activeId = TtsVoiceChoices.heard(ttsVoiceProfileId, ttsVoiceTone).id

    IosSheet(onDismissRequest = onDismiss, title = "Chọn giọng đọc") {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { name ->
                    FilterChip8(
                        label = name,
                        selected = category == name,
                        onClick = { category = name },
                        containerColor = colors.cardHigh
                    )
                }
            }
            if (filtered.isEmpty()) {
                Text(
                    text = "Chưa có giọng nào trong nhóm này",
                    style = AppText.secondary,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(filtered) { profile ->
                        val isSelected = profile.id == activeId
                        InsetRow(
                            title = profile.name,
                            subtitle = TtsVoiceChoices.subtitle(profile),
                            modifier = Modifier.semantics { selected = isSelected },
                            onClick = {
                                onVoiceProfileChange(profile.id)
                                onDismiss()
                            },
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
                    item { InsetGroupFooter(TtsVoiceChoices.AI_VOICE_NOTE, Modifier.padding(bottom = 12.dp)) }
                }
            }
        }
    }
}
