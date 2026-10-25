package com.truyencloud.compose.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Bán kính kiểu iOS, hơi tròn hơn Bắc Âu thuần. Tròn hẳn (PillShape) chỉ cho avatar và hành động inline nhỏ.

/** Thẻ và nhóm danh sách inset. */
val CardShape = RoundedCornerShape(16.dp)

/** Nhóm trong danh sách inset (Cài đặt, danh sách tuỳ chọn). */
val GroupShape = RoundedCornerShape(16.dp)

/** Nút 50dp. */
val ButtonShape = RoundedCornerShape(12.dp)

/** Ô nhập. */
val InputShape = RoundedCornerShape(12.dp)

/** Chip lọc: hình chữ nhật bo 10, không phải viên thuốc. */
val ChipShape = RoundedCornerShape(10.dp)

val SegmentedTrackShape = RoundedCornerShape(10.dp)
val SegmentedThumbShape = RoundedCornerShape(8.dp)

/** Ô biểu tượng vuông 28dp trong hàng danh sách inset. */
val IconTileShape = RoundedCornerShape(8.dp)

/** Tờ trượt từ dưới lên: bo 24 ở hai góc trên. */
val BottomSheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/** Hộp thoại kiểu iOS. */
val DialogShape = RoundedCornerShape(18.dp)

/** Bìa sách: bo nhỏ 6dp kèm đường kẻ mảnh. */
val BookCoverShape = RoundedCornerShape(6.dp)

/** Chỉ cho avatar và nút tròn nhỏ. */
val PillShape = CircleShape

/** Shapes của Material: AlertDialog / ModalBottomSheet / Button / TextField không đặt shape sẽ dùng các giá trị này. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)
