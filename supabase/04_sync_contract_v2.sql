-- 04_sync_contract_v2.sql — bổ sung cột cho hợp đồng đồng bộ mới (2026-09).
--
-- CHỈ CẦN CHẠY khi bật Supabase (SUPABASE_URL / SUPABASE_SERVICE_KEY trong server/.env).
-- Mặc định máy chủ chạy SQLite cục bộ và tự tạo bảng, không cần tệp này.
-- Chạy SAU 03_internal_backend_sync.sql; an toàn khi chạy lại (IF NOT EXISTS).

-- Tiến độ đọc: thời điểm phía CLIENT (epoch ms) để giải quyết xung đột "ghi sau thắng"
ALTER TABLE public.reading_progress_backend
    ADD COLUMN IF NOT EXISTS updated_at_ms BIGINT NOT NULL DEFAULT 0;

-- Bookmark: sửa đổi + xoá mềm (tombstone) để thiết bị khác biết bookmark đã bị xoá
ALTER TABLE public.bookmarks
    ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS deleted_at BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS server_updated_at BIGINT NOT NULL DEFAULT 0;

-- Từ vựng: xoá mềm + mốc nhận của máy chủ (cho đồng bộ tăng dần theo ?since=)
ALTER TABLE public.saved_words
    ADD COLUMN IF NOT EXISTS deleted_at BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS server_updated_at BIGINT NOT NULL DEFAULT 0;

-- Nhật ký ôn tập: khoá theo (word_id, reviewed_at) thay vì id tự tăng của từng thiết bị
ALTER TABLE public.review_logs
    ADD COLUMN IF NOT EXISTS word_id TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS server_updated_at BIGINT NOT NULL DEFAULT 0;

-- Lưu ý: PostgREST của Supabase mặc định chỉ trả 1000 dòng mỗi truy vấn. Bộ đếm "đọc nhiều"
-- của GET /v1/novels/discover và các truy vấn đồng bộ lớn cần phân trang nếu vượt mức này.
