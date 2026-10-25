package com.truyencloud.compose.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.NovelCard
import com.truyencloud.compose.components.SourceChipBar
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.RemoteSuggestItem
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.library.LibraryListLogic
import com.truyencloud.shared.library.SearchSuggestionSection

/**
 * Lớp tìm kiếm phủ lên Home (nằm dưới [HomeSearchHeader]). Kết quả chia theo nơi truyện đang ở: trong tủ sách của bạn (đọc
 * ngay), có sẵn trên máy chủ, hoặc tìm thấy trực tuyến. [libraryKeys] để đánh dấu "Trong tủ".
 *
 * Khi chưa gõ gì thì hiện [suggestions] (riêng cho tài khoản đang dùng). [onSuggestionClick] là chạm một chip gợi ý
 * (được ghi vào lịch sử); [onQuickSearch] chỉ tìm lại từ khoá (nút liên kết, "Thử lại") và không ghi gì.
 */
@Composable
fun SearchOverlayView(
    searchQuery: String,
    isSearching: Boolean,
    searchResults: List<Novel>,
    searchError: String?,
    selectedSource: String,
    onSelectSource: (String) -> Unit,
    onSelectNovel: (Novel) -> Unit,
    onQuickSearch: (String) -> Unit,
    suggestions: List<SearchSuggestionSection>,
    onSuggestionClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
    liveSuggestions: List<RemoteSuggestItem> = emptyList(),
    libraryKeys: Set<String> = emptySet(),
    discover: DiscoverUiState = DiscoverUiState()
) {
    val trimmedQuery = searchQuery.trim()
    val isUrl = trimmedQuery.startsWith("http://", ignoreCase = true) || trimmedQuery.startsWith("https://", ignoreCase = true)

    val grouped = remember(searchResults, libraryKeys) {
        val (mine, others) = searchResults.partition { LibraryListLogic.isInLibrary(it, libraryKeys) }
        Triple(mine, others.filter { it.sourceId == "local" }, others.filter { it.sourceId != "local" })
    }

    Column(modifier = modifier.fillMaxSize().background(LocalCustomColors.current.bg)) {
        SourceChipBar(selectedSource = selectedSource, onSelectSource = onSelectSource)

        if (!isUrl && trimmedQuery.isNotEmpty() && liveSuggestions.isNotEmpty()) {
            LiveServerSuggestionsRow(items = liveSuggestions, onPick = onSuggestionClick)
        }

        if (isUrl) UrlActionCard(onClick = { onQuickSearch(trimmedQuery) })

        // Bàn phím che phần dưới: chừa chỗ để cuộn tới kết quả / gợi ý cuối
        Box(modifier = Modifier.weight(1f).fillMaxWidth().imeAboveTabBar()) {
            when {
                isSearching -> SearchingIndicator(isUrl = isUrl)
                searchError != null -> SearchErrorView(
                    message = searchError,
                    onRetry = { if (searchQuery.isNotBlank()) onQuickSearch(searchQuery) }
                )
                searchResults.isNotEmpty() -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = Spacing.coverWidth),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                    contentPadding = PaddingValues(start = Spacing.pageH, end = Spacing.pageH, top = Spacing.xs, bottom = Spacing.lg),
                    modifier = Modifier.fillMaxSize()
                ) {
                    resultSection("Trong tủ sách của bạn", "Chạm để đọc ngay", grouped.first, inLibrary = true, discover = discover, onSelectNovel = onSelectNovel)
                    resultSection("Có sẵn trên máy chủ", "Chạm để xem và thêm vào tủ", grouped.second, inLibrary = false, discover = discover, onSelectNovel = onSelectNovel)
                    resultSection("Tìm thấy trực tuyến", "Chạm để xem và thêm vào tủ", grouped.third, inLibrary = false, discover = discover, onSelectNovel = onSelectNovel)
                }
                // Đã dán liên kết nhưng chưa chạm thẻ "Đã nhận ra liên kết": chưa tìm gì nên cũng chưa có gì để báo "không thấy"
                isUrl -> Unit
                searchQuery.isNotBlank() -> EmptyState(
                    message = "Không tìm thấy kết quả",
                    subMessage = "Thử tìm với từ khóa khác hoặc chuyển nguồn truyện.",
                    icon = TruyenCloudIcons.Search,
                    modifier = Modifier.fillMaxSize()
                )
                else -> SearchSuggestions(
                    sections = suggestions,
                    onSuggestionClick = onSuggestionClick,
                    onClearHistory = onClearHistory
                )
            }
        }
    }
}

/** Một nhóm kết quả: tiêu đề đầy hàng (13sp Medium, kèm số lượng) + dòng gợi ý + các thẻ truyện. Nhóm rỗng thì bỏ qua. */
private fun LazyGridScope.resultSection(
    title: String,
    hint: String,
    novels: List<Novel>,
    inLibrary: Boolean,
    discover: DiscoverUiState,
    onSelectNovel: (Novel) -> Unit
) {
    if (novels.isEmpty()) return
    item(span = FullSpan) {
        val colors = LocalCustomColors.current
        Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
            Text(text = "$title (${novels.size})", style = AppText.groupHeader, color = colors.textSecondary)
            Text(text = hint, style = AppText.groupFooter, color = colors.textTertiary, modifier = Modifier.padding(top = 2.dp))
        }
    }
    // Không đặt key: kết quả tìm kiếm có thể trùng id giữa các nguồn, key trùng sẽ làm sập LazyGrid
    items(novels) { novel ->
        NovelCard(
            novel = novel,
            inLibrary = inLibrary,
            overnightEnabled = discover.isOvernight(novel),
            translatedChapters = discover.translatedOf(novel),
            onClick = { onSelectNovel(novel) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LiveServerSuggestionsRow(
    items: List<RemoteSuggestItem>,
    onPick: (String) -> Unit
) {
    val colors = LocalCustomColors.current
    val shape = RoundedCornerShape(10.dp)
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = Spacing.pageH, vertical = 6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items) { item ->
            val target = item.query.ifBlank { item.label }
            val badge = when (item.kind) {
                "local" -> "Kho 1TB"
                "title" -> "Truyện hot"
                else -> "Từ điển"
            }
            Column(
                modifier = Modifier
                    .clip(shape)
                    .background(colors.card)
                    .border(1.dp, colors.hairline, shape)
                    .clickable { if (target.isNotBlank()) onPick(target) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "$badge · ${item.label.ifBlank { target }}",
                    style = AppText.groupHeader,
                    color = colors.textPrimary
                )
                if (item.sub.isNotBlank()) {
                    Text(
                        text = item.sub,
                        style = AppText.groupFooter,
                        color = colors.textTertiary
                    )
                }
            }
        }
    }
}
