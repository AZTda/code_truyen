package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.SavedWord

/** Từ vựng nhóm theo sáu cụm chủ đề ngữ nghĩa (xem [VocabularyDomains]). Cụm chưa có từ vẫn hiện để biết còn nhóm nào. */
@Composable
fun SemanticDomainClustersView(
    domainsMap: Map<String, List<SavedWord>>,
    onSelectDomain: (String) -> Unit,
    onWeaveStoryForDomain: (String) -> Unit,
    onSpeak: (String) -> Unit,
    headerContent: (@Composable () -> Unit)? = null,
    bottomPadding: Dp = Spacing.lg
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        if (headerContent != null) {
            item { headerContent() }
        }
        items(VocabularyDomains.all, key = { it.id }) { domain ->
            val words = domainsMap[domain.id].orEmpty()
            CollectionCard(
                title = domain.title,
                subtitle = domain.subtitle,
                words = words,
                onSpeak = onSpeak,
                primaryLabel = "Ôn thẻ cụm này",
                onPrimary = { onSelectDomain(domain.id) },
                onWeave = { onWeaveStoryForDomain(domain.id) },
                icon = domain.icon,
                badge = "${words.size} từ",
                emptyText = "Chưa có từ thuộc nhóm này. Đọc thêm tiểu thuyết để thấy từ mới ở đây.",
                modifier = Modifier.padding(horizontal = Spacing.pageH)
            )
        }
    }
}
