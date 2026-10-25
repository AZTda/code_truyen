package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont

/** Thêm tay một thuật ngữ (không cần AI): nguyên văn (nên là chữ Hán chép từ bản gốc) và cách dịch muốn dùng. */
@Composable
internal fun GlossaryAddForm(
    onAdd: (source: String, target: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    var source by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }

    FlatCard(modifier = modifier.fillMaxWidth(), outlined = true) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ReaderInputField(
                value = source,
                onValueChange = { source = it },
                placeholder = "Nguyên văn trong bản gốc (vd. 岑星)",
                fontFamily = ReaderFont,
                containerColor = colors.cardHigh
            )
            Spacer(modifier = Modifier.height(8.dp))
            ReaderInputField(
                value = target,
                onValueChange = { target = it },
                placeholder = "Cách dịch muốn dùng (vd. Sầm Tinh)",
                containerColor = colors.cardHigh
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    text = "Thêm",
                    onClick = {
                        onAdd(source, target)
                        source = ""
                        target = ""
                    },
                    modifier = Modifier.weight(1f)
                )
                TextActionButton(text = "Đóng", onClick = onCancel)
            }
        }
    }
}
