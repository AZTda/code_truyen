package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.PillShape
import com.truyencloud.shared.data.UserProfile

/** Tên hiển thị; hồ sơ chưa đặt tên thì gọi chung là "Độc giả" (cả ở dòng tên lẫn chữ cái trong ảnh đại diện). */
private fun UserProfile.shownName(): String = displayName.trim().ifBlank { "Độc giả" }

/**
 * Hàng hồ sơ ở đầu Cài đặt, là một nhóm riêng: ảnh đại diện 56dp, tên, dòng phụ và giới thiệu. Người đã đăng nhập thấy
 * mũi tên (chạm để chỉnh sửa hồ sơ); khách thấy nút "Đăng nhập" ngay dưới, rộng hết hàng nên không bị chen với tên ở màn
 * hẹp hay cỡ chữ lớn. Không còn ảnh bìa, cấp độ hay mục tiêu.
 */
@Composable
fun UserProfileHeader(
    profile: UserProfile,
    onEditProfile: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    InsetGroup(
        modifier = modifier,
        // Đăng ký đã đóng ở máy chủ (403): không hứa "Tạo tài khoản" nữa
        footer = if (profile.isLoggedIn) null else "Đăng ký tài khoản do quản trị viên cấp."
    ) {
        ProfileRow(profile = profile, onEditProfile = onEditProfile, onLogin = onLogin)
    }
}

@Composable
private fun ProfileRow(profile: UserProfile, onEditProfile: () -> Unit, onLogin: () -> Unit) {
    val colors = LocalCustomColors.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Chỉnh sửa hồ sơ", role = Role.Button, onClick = onEditProfile)
                .heightIn(min = 88.dp)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(profile)
                Spacer(modifier = Modifier.width(16.dp))
                ProfileTexts(profile, modifier = Modifier.weight(1f))
                if (profile.isLoggedIn) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = TruyenCloudIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (!profile.isLoggedIn) {
                Spacer(modifier = Modifier.height(12.dp))
                TonalButton(text = "Đăng nhập", onClick = onLogin, modifier = Modifier.fillMaxWidth())
            }
        }
        HairlineDivider()
    }
}

/** Ảnh đại diện 56dp. Chữ cái đầu nằm dưới ảnh nên vẫn hiện khi ảnh chưa tải được (mất mạng, địa chỉ hỏng). */
@Composable
private fun ProfileAvatar(profile: UserProfile) {
    val colors = LocalCustomColors.current
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(PillShape)
            .background(colors.accentSoft),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = profile.shownName().take(1).uppercase(),
            style = AppText.screenTitle,
            color = colors.onAccentSoft
        )
        if (profile.avatarUrl.isNotBlank()) {
            AsyncImage(
                model = profile.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ProfileTexts(profile: UserProfile, modifier: Modifier) {
    val colors = LocalCustomColors.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = profile.shownName(),
                style = AppText.sectionHeader,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (profile.vipTier.isNotBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = profile.vipTier,
                    style = AppText.caption,
                    color = colors.onAccentSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(ChipShape)
                        .background(colors.accentSoft)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        // Email chỉ hiện cho tài khoản đã đăng nhập (khách không có email; trước đây hiện cả "ID: guest")
        Text(
            text = when {
                !profile.isLoggedIn -> "Chưa đăng nhập"
                profile.email.isNotBlank() -> profile.email
                else -> "Đã đăng nhập"
            },
            style = AppText.secondary,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (profile.bio.isNotBlank()) {
            Text(
                text = profile.bio,
                style = AppText.secondary,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
