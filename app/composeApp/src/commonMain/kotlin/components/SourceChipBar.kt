package com.truyencloud.compose.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.Spacing

data class SourceItem(val id: String, val name: String, val badge: String = "")

/** Thanh lọc theo nguồn truyện trong tìm kiếm: các [FilterChip8] cuộn ngang, nguồn lấy từ [NovelSources.filters]. */
@Composable
fun SourceChipBar(
    selectedSource: String,
    onSelectSource: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageH),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NovelSources.filters.forEach { source ->
            FilterChip8(
                label = source.name,
                selected = selectedSource == source.id,
                onClick = { onSelectSource(source.id) },
                trailingText = source.badge.takeIf { it.isNotBlank() }
            )
        }
    }
}
