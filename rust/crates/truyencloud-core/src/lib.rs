//! Lõi nghiệp vụ dùng chung của hệ sinh thái TruyenCloud (Rust Edition).
//!
//! Cung cấp các mô-đun độc lập nền tảng cho cả Backend (`truyencloud-server`)
//! và Ứng dụng Độc giả (`truyencloud-app`):
//! - [`models`]: Các cấu trúc dữ liệu chuẩn hoá (Novel, Chapter, User, Vocabulary, Glossary, OTA).
//! - [`crypto`]: Xác thực mật khẩu `PBKDF2-HMAC-SHA256` & phát hành/xác minh token `HMAC-SHA256` tương thích 100% với bản Python.
//! - [`fsrs`]: Thuật toán lặp lại ngắt quãng `FSRS v4.5` (Spaced Repetition) tính toán độ bền trí nhớ.
//! - [`text_paging`]: Bộ làm sạch quảng cáo, chuẩn hoá văn bản CJK/Việt và chia trang đọc tốc độ cao.
//! - [`hanviet`]: Bộ từ điển Hán-Việt, chuẩn hoá tên truyện và phân tích từ gốc tiếng Anh (`Lemma`).
//! - [`epub`]: Bộ đóng gói sách điện tử `.EPUB (v3)` trực tiếp trong bộ nhớ RAM.

pub mod crypto;
pub mod epub;
pub mod fsrs;
pub mod hanviet;
pub mod models;
pub mod text_paging;
