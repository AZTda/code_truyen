# TruyenCloud — Pure Rust Edition (`v1.6.0`)

Phiên bản viết lại hoàn toàn bằng **Rust** (`Workspace` gồm 3 crates độc lập), nằm riêng trong thư mục [`rust/`](file:///Users/diemtran/Library/Mobile%20Documents/com~apple~CloudDocs/Desktop/TruyenCloud/rust), **không làm thay đổi hay ảnh hưởng** tới thư mục `server/` (Python) và `app/` (Kotlin Multiplatform) hiện có.

---

## 1. Cấu trúc 3 Crates trong Workspace (`rust/`)

1. **[`crates/truyencloud-core`](file:///Users/diemtran/Library/Mobile%20Documents/com~apple~CloudDocs/Desktop/TruyenCloud/rust/crates/truyencloud-core)** — Thư viện Lõi nghiệp vụ dùng chung:
   - `crypto.rs`: Xác thực mật khẩu `PBKDF2-HMAC-SHA256` (`100.000` vòng) và cấp/kiểm tra token `HMAC-SHA256` tương thích 100% với bản Python.
   - `fsrs.rs`: Bộ lập lịch lặp lại ngắt quãng `FSRS v4.5` thuần Rust.
   - `text_paging.rs`: Bộ lọc quảng cáo trang nguồn (`69shuba`, `uukanshu`, `piaotia`) và chia trang văn bản tốc độ cao.
   - `hanviet.rs`: Từ điển cụm Hán-Việt, bỏ dấu tiếng Việt không phân biệt dấu và phân tích từ gốc tiếng Anh (`Lemma`).
   - `epub.rs`: Bộ đóng gói sách điện tử `.EPUB (v3)` trực tiếp trong bộ nhớ RAM.

2. **[`crates/truyencloud-server`](file:///Users/diemtran/Library/Mobile%20Documents/com~apple~CloudDocs/Desktop/TruyenCloud/rust/crates/truyencloud-server)** — Máy chủ Backend `Axum + Tokio + Rusqlite + Scraper`:
   - Tương thích trực tiếp với cơ sở dữ liệu `server/data/truyencloud.db`, `users.json`, `glossaries/` và giao diện `/admin`.
   - Mặc định chạy ở cổng `8081` để có thể bật song song với Backend Python (cổng `8000`) mà không xung đột.

3. **[`crates/truyencloud-app`](file:///Users/diemtran/Library/Mobile%20Documents/com~apple~CloudDocs/Desktop/TruyenCloud/rust/crates/truyencloud-app)** — Ứng dụng Độc giả Native GUI (`eframe / egui`):
   - Tích hợp đầy đủ 4 phân khu: **Khám phá & Lọc nhanh 366+ truyện**, **Chi tiết truyện & Xuất `.EPUB` + Chọn giọng `VieNeu-TTS` + Trình đọc phân trang**, **Ôn từ vựng `FSRS` Lật thẻ Flashcard**, **Nhật ký Lịch sử đọc/nghe** và **Thanh `MiniAudiobookBar` nổi**.

---

## 2. Cách biên dịch, chạy kiểm thử và khởi động

```bash
cd rust

# 1. Chạy toàn bộ Unit Test của Workspace Rust
cargo test --workspace

# 2. Khởi chạy Backend Rust (lắng nghe tại http://127.0.0.1:8081)
cargo run --release -p truyencloud-server

# 3. Khởi chạy Ứng dụng Độc giả Giao diện Native Rust (GUI)
cargo run --release -p truyencloud-app

# 4. Kiểm tra nhanh Ứng dụng ở chế độ dòng lệnh (CLI)
cargo run -p truyencloud-app -- --check
```
