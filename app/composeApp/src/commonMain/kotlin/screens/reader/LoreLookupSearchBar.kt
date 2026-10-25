package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons

/** Ô nhập từ cần tra + nút "Tra cứu" (cũng chạy khi bấm Tìm trên bàn phím). */
@Composable
internal fun LoreLookupSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onLookup: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReaderInputField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Nhập từ Hán Việt hoặc nhân vật…",
            modifier = Modifier.weight(1f),
            leadingIcon = TruyenCloudIcons.Search,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onLookup(query) })
        )
        TonalButton(text = "Tra cứu", onClick = { onLookup(query) }, enabled = query.isNotBlank())
    }
}
