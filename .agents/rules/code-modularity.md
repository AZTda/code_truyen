# Quy Tắc Kiến Trúc Sạch & Giới Hạn Kích Thước Tệp (Clean Architecture & Code Modularity Rule)

> **MANDATORY ENFORCEMENT**: Rule này áp dụng bắt buộc cho mọi hành động lập trình, tạo mới hoặc chỉnh sửa mã nguồn trong toàn bộ dự án TruyenCloud.

---

## 1. Giới Hạn Kích Thước Tệp (File Size Budget)

1. **Ngưỡng tối đa tuyệt đối (Hard Limit)**:
   - **350 dòng** cho bất kỳ tệp mã nguồn nào (`.kt`, `.py`, `.swift`, `.ts`, `.js`).
2. **Ngưỡng cảnh báo (Soft Warning)**:
   - **250 dòng**. Khi một file bắt đầu vượt quá 250 dòng, lập trình viên / Agent **phải chủ động bóc tách** các component con, controller, delegate hoặc helper functions sang file mới trước khi viết tiếp.
3. **Nghiêm cấm "God Files"**:
   - **CẤM** tạo một file gom chung tất cả Icons (như `TruyenCloudIcons.kt` > 3,000 dòng). Icons phải chia theo danh mục nghiệp vụ (`NavigationIcons.kt`, `ReaderIcons.kt`, `AudioIcons.kt`...).
   - **CẤM** tạo "God ViewModel" (> 400 dòng). Phải dùng Delegate / Controller pattern (ví dụ: `ReaderTtsCoordinator`, `ChapterPagingController`).
   - **CẤM** nhét inline các BottomSheet, Dialog, Custom Canvas hoặc Renderers phức tạp vào trong file `*Screen.kt`. Mỗi Sheet/Dialog/Card phải là một file riêng biệt.

---

## 2. Nguyên Lý Một File Một Chức Năng (Single Responsibility Principle - SRP)

| Loại Tệp | Trách Nhiệm Duy Nhất | Điều Cấm |
| :--- | :--- | :--- |
| `*Screen.kt` | Điều phối bố cục chính (Scaffold, TopBar, BottomBar, phân luồng các state và hiển thị Sheet). | Không viết inline logic tính toán phức tạp, không định nghĩa nội dung chi tiết của Sheet/Dialog. |
| `*Sheet.kt` / `*Dialog.kt` | Hiển thị và xử lý tương tác bên trong modal bottom sheet hoặc dialog cụ thể. | Không chứa logic gọi API trực tiếp hay ôm đồm nhiều màn hình con. |
| `*Icons.kt` | Chứa nhóm vector icon của một phân hệ nghiệp vụ cụ thể (< 300 dòng). | Không nhét icon của toàn bộ app vào 1 file duy nhất. |
| `*ViewModel.kt` | Quản lý UI State và chuyển tiếp sự kiện người dùng đến Repository/UseCase (< 350 dòng). | Không viết logic xử lý thuật toán phức tạp trực tiếp bên trong (hãy tách ra UseCase / Helper). |
| Backend Services (`.py`) | Chia thành Router, Pydantic Schema, Domain Service, Prompt Templates, External Client. | Không để router lẫn lộn với background worker hay prompt AI dài hàng trăm dòng. |

---

## 3. Quy Trình Tự Kiểm Tra (Self-Verification Checklist Trước Khi Bàn Giao)

Trước khi Agent kết thúc bất kỳ tác vụ nào hoặc thông báo hoàn thành tính năng cho người dùng:
1. Chạy công cụ kiểm tra độ dài tệp:
   ```bash
   bash tools/check_file_budget.sh
   ```
2. Nếu phát hiện tệp mới hoặc tệp vừa sửa vượt quá **350 dòng**:
   - Phải **ngay lập tức refactor chia nhỏ** tệp đó ra trước khi phản hồi người dùng.
3. Luôn đảm bảo nguyên tắc **Zero Breaking Changes**:
   - Khi tách file Kotlin, sử dụng Extension Properties hoặc cùng package name để giữ nguyên chữ ký gọi hàm của toàn bộ codebase.
