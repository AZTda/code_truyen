package com.truyencloud.compose.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.InsetSwitchRow
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.shared.api.ModelItem
import com.truyencloud.shared.api.OvernightStatusResponse
import com.truyencloud.shared.api.TranslateApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Số chương dịch trước: (giá trị lưu, nhãn). */
private val PREFETCH_OPTIONS = listOf(0 to "Tắt", 1 to "1 chương", 3 to "3 chương", 5 to "5 chương")

/**
 * Nhóm "Dịch và AI": model dịch, hồ sơ văn phong, dịch trước chương kế tiếp và tự thu thập lại. Tất cả là cấu hình DÙNG CHUNG
 * trên máy chủ, nên người dùng thường chỉ xem (đổi model, làm mới danh mục, dịch trước, thu thập lại đều khoá và có chú thích);
 * mở "Hồ sơ văn phong" cho họ thấy giá trị hiện tại nhưng không sửa được.
 */
@Composable
fun TranslationAiSection(
    isAdmin: Boolean,
    activeModel: String,
    models: List<ModelItem>,
    isRefreshingModels: Boolean,
    modelStatusMessage: String,
    onOpenModelPicker: () -> Unit,
    onRefreshModels: () -> Unit,
    selectedGenre: String,
    onGenreChange: (String) -> Unit,
    sinoVietnameseRatio: String,
    onSinoVietnameseRatioChange: (String) -> Unit,
    customPrompt: String,
    onCustomPromptChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
    isSavingProfile: Boolean,
    profileSavedMessage: String,
    prefetchChapters: Int,
    onPrefetchChange: (Int) -> Unit,
    autoRecrawl: Boolean,
    onAutoRecrawlChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var editorOpen by remember { mutableStateOf(false) }
    var overnightStatus by remember { mutableStateOf<OvernightStatusResponse?>(null) }
    var refreshingOvernight by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val translateApi = remember { TranslateApi() }

    fun loadOvernightStatus() {
        scope.launch {
            refreshingOvernight = true
            try {
                overnightStatus = translateApi.getOvernightStatus()
            } catch (_: Exception) {
            } finally {
                refreshingOvernight = false
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            loadOvernightStatus()
            delay(12_000L)
        }
    }
    val activeModelName = models.firstOrNull { it.id == activeModel }?.name ?: activeModel
    // Giá trị máy chủ đang giữ mà không có trong danh sách thì thêm vào để ô chọn không tô nhầm "Tắt"
    val prefetchOptions = if (PREFETCH_OPTIONS.any { it.first == prefetchChapters }) PREFETCH_OPTIONS
    else (PREFETCH_OPTIONS + (prefetchChapters to "$prefetchChapters chương")).sortedBy { it.first }
    // Chữ lớn hoặc có thêm một mức lạ (5 ô): "1 chương" không còn vừa một ô, rút còn số (tiêu đề đã nói là số chương)
    val compactLabels = isLargeFontScale() || prefetchOptions.size > PREFETCH_OPTIONS.size

    InsetGroup(
        modifier = modifier,
        header = "Dịch và AI",
        footer = if (isAdmin) null else ADMIN_ONLY_TEXT
    ) {
        // Chữ lớn: tên model xuống dòng phụ, nếu không cột tiêu đề chỉ còn vài ký tự
        val large = isLargeFontScale()
        InsetRow(
            title = "Model dịch",
            subtitle = if (large) "$activeModelName · Có ${models.size} model sẵn sàng" else "Có ${models.size} model sẵn sàng",
            value = if (large) null else activeModelName,
            icon = TruyenCloudIcons.Hub,
            showChevron = true,
            onClick = onOpenModelPicker
        )
        val ov = overnightStatus
        val ovSubtitle = when {
            ov == null -> "Gemini Flash Medium + Qwen3-4B (chạm để kiểm tra)"
            ov.enabled_novels.isNotEmpty() -> {
                val names = ov.enabled_novels.joinToString(", ") { item ->
                    if (item.total_chapters > 0 && item.translated_chapters > 0) {
                        "${item.title} (${item.translated_chapters}/${item.total_chapters})"
                    } else {
                        item.title
                    }
                }
                val batchInfo = if (ov.current_batch.isNotEmpty()) " · Đang dịch ch.${ov.current_batch.first()}..${ov.current_batch.last()}" else ""
                "Đang bật (${ov.enabled_novels.size}): $names$batchInfo"
            }
            else -> "Gemini Flash Medium + Qwen3-4B · Chưa có truyện nào bật dịch ngầm"
        }
        InsetRow(
            title = "Tiến trình Dịch ngầm ban đêm",
            subtitle = ovSubtitle,
            value = if (ov?.running == true) "Hoạt động" else null,
            icon = TruyenCloudIcons.MoonStar,
            onClick = if (refreshingOvernight) null else ::loadOvernightStatus,
            trailing = { if (refreshingOvernight) RowSpinner() }
        )
        if (isAdmin) {
            InsetRow(
                title = "Làm mới danh sách model",
                subtitle = modelStatusMessage.ifBlank { null },
                icon = TruyenCloudIcons.Refresh,
                iconTint = InsetIconTint.Neutral,
                onClick = if (isRefreshingModels) null else onRefreshModels,
                trailing = { if (isRefreshingModels) RowSpinner() }
            )
        }
        InsetRow(
            title = "Hồ sơ văn phong và chỉ dẫn dịch",
            subtitle = selectedGenre,
            icon = TruyenCloudIcons.Wand,
            showChevron = true,
            onClick = { editorOpen = true }
        )
        SettingsFormRow(
            title = "Dịch trước chương kế tiếp khi đang đọc",
            subtitle = "Hệ thống tự gửi chương sắp đọc vào máy chủ để chuyển chương mượt mà không độ trễ.",
            icon = TruyenCloudIcons.CloudDownload,
            iconTint = InsetIconTint.Neutral,
            enabled = isAdmin
        ) {
            OnCardTrack {
                SegmentedControl(
                    options = prefetchOptions.map { if (!compactLabels) it.second else if (it.first == 0) "Tắt" else it.first.toString() },
                    selectedIndex = prefetchOptions.indexOfFirst { it.first == prefetchChapters }.coerceAtLeast(0),
                    onSelect = { onPrefetchChange(prefetchOptions[it].first) },
                    enabled = isAdmin
                )
            }
        }
        InsetSwitchRow(
            title = "Tự động thu thập lại khi thiếu nội dung",
            subtitle = "Tự kiểm tra và cào lại nguồn gốc nếu phát hiện chương thiếu chữ",
            checked = autoRecrawl,
            onCheckedChange = onAutoRecrawlChange,
            icon = TruyenCloudIcons.Sync,
            iconTint = InsetIconTint.Neutral,
            enabled = isAdmin
        )
    }

    if (editorOpen) {
        TranslationProfileSheet(
            selectedGenre = selectedGenre,
            onGenreChange = onGenreChange,
            sinoVietnameseRatio = sinoVietnameseRatio,
            onSinoVietnameseRatioChange = onSinoVietnameseRatioChange,
            customPrompt = customPrompt,
            onCustomPromptChange = onCustomPromptChange,
            onSaveProfile = onSaveProfile,
            isSaving = isSavingProfile,
            saveMessage = profileSavedMessage,
            onDismiss = { editorOpen = false },
            isAdmin = isAdmin
        )
    }
}
