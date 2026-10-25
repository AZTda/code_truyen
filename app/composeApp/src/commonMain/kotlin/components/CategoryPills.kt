package com.truyencloud.compose.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.Spacing

/** Hàng thể loại cuộn ngang: mỗi thể loại là một [FilterChip8]. Tên cũ "pills" vì trước đây là viên thuốc; nay chip chữ nhật. */
@Composable
fun CategoryPills(
    categories: List<String>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.pageH)
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        categories.forEach { category ->
            FilterChip8(
                label = category,
                selected = category == selectedCategory,
                onClick = { onSelectCategory(category) }
            )
        }
    }
}
