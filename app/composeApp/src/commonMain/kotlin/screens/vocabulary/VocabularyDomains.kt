package com.truyencloud.compose.screens.vocabulary

import androidx.compose.ui.graphics.vector.ImageVector
import com.truyencloud.compose.components.icons.TruyenCloudIcons

data class DomainMetadata(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

/** Sáu cụm chủ đề ngữ nghĩa của sổ từ; một nguồn duy nhất cho tên hiển thị ở danh sách cụm và dải báo khi ôn theo cụm. */
object VocabularyDomains {
    val all: List<DomainMetadata> = listOf(
        DomainMetadata(
            id = "TIME_TRANSIENCE",
            title = "Thời gian và biến đổi",
            subtitle = "Khoảnh khắc phù du, dòng thời gian và sự vô thường",
            icon = TruyenCloudIcons.DomainTime
        ),
        DomainMetadata(
            id = "FATE_SERENDIPITY",
            title = "Duyên phận và tình cờ",
            subtitle = "Cơ duyên may mắn, định mệnh và tao ngộ diệu kỳ",
            icon = TruyenCloudIcons.DomainFate
        ),
        DomainMetadata(
            id = "INTELLECT_ACADEMIA",
            title = "Học thuật và tri thức",
            subtitle = "Tư duy phản biện, lý thuyết và triết học sâu sắc",
            icon = TruyenCloudIcons.DomainAcademic
        ),
        DomainMetadata(
            id = "WORKPLACE_CAREER",
            title = "Công sở và đàm phán",
            subtitle = "Chiến lược kinh doanh, quản trị và bước ngoặt sự nghiệp",
            icon = TruyenCloudIcons.DomainWorkplace
        ),
        DomainMetadata(
            id = "PSYCHOLOGY_EMOTION",
            title = "Cảm xúc và nội tâm",
            subtitle = "Thế giới tâm lý, trạng thái cảm xúc và rung cảm sâu kín",
            icon = TruyenCloudIcons.DomainEmotion
        ),
        DomainMetadata(
            id = "AESTHETICS_SCENERY",
            title = "Không gian và mỹ cảm",
            subtitle = "Cảnh sắc thiên nhiên, nghệ thuật thị giác và phong cảnh",
            icon = TruyenCloudIcons.DomainAesthetics
        )
    )

    /** Mã lạ rơi về nhóm cuối, giống cách phân loại của kho từ. */
    fun titleOf(id: String?): String = all.firstOrNull { it.id == id }?.title ?: all.last().title
}
