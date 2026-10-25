package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Thao tác chỉnh sửa: thêm, bớt, xoá, sửa, lưu, gửi. */
interface EditorActionIcons {
    val Add: ImageVector get() = EditorHolder.add
    val Remove: ImageVector get() = EditorHolder.remove
    val Delete: ImageVector get() = EditorHolder.delete
    val Edit: ImageVector get() = EditorHolder.edit
    val Save: ImageVector get() = EditorHolder.save
    val FolderSpecial: ImageVector get() = EditorHolder.folderSpecial
    val Send: ImageVector get() = EditorHolder.send
}

private object EditorHolder {
    val add by lazyIcon("Add", "M12 5v14 M5 12h14")
    val remove by lazyIcon("Remove", "M5 12h14")
    val delete by lazyIcon(
        "Delete",
        "M4 7h16", "M9 7V4.5a.5.5 0 0 1 .5-.5h5a.5.5 0 0 1 .5.5V7",
        "M6 7l.9 12a2 2 0 0 0 2 1.9h6.2a2 2 0 0 0 2-1.9L18 7", "M10 11v6 M14 11v6"
    )
    val edit by lazyIcon(
        "Edit",
        "M20.7 6.4a2.1 2.1 0 0 0-3-3L4 17a2 2 0 0 0-.5.8l-1.2 3.9a.5.5 0 0 0 .6.6l3.9-1.2A2 2 0 0 0 7 20.6Z",
        "m15 5 4 4"
    )
    val save by lazyIcon(
        "Save",
        "M5 3.5h11l3.5 3.5V19a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 19V5A1.5 1.5 0 0 1 5 3.5Z",
        "M7.5 3.5v5h7v-5", "M7.5 20.5v-6h9v6"
    )
    val folderSpecial by lazyIcon(
        "FolderSpecial",
        "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"
    )
    val send by lazyIcon(
        "Send",
        "M14.54 21.69a.5.5 0 0 0 .94-.02l6.5-19a.5.5 0 0 0-.64-.64l-19 6.5a.5.5 0 0 0-.02.94l7.93 3.18a2 2 0 0 1 1.11 1.11Z",
        "m21.85 2.15-10.94 10.94"
    )
}
