# Hướng Dẫn Thiết Lập Supabase Cho TruyenCloud 2026

## 1. Tạo Project trên Supabase
1. Truy cập [database.new](https://database.new) hoặc [supabase.com/dashboard](https://supabase.com/dashboard)
2. Tạo một project mới (ví dụ: `TruyenCloud-2026`).
3. Lưu lại:
   - **Project URL** (VD: `https://xyzcompany.supabase.co`)
   - **Anon Public Key** (dùng cho client app)
   - **Service Role Secret Key** (dùng cho backend server trên Mac/Ubuntu)

## 2. Chạy Schema SQL
1. Mở menu **SQL Editor** trong Supabase Dashboard.
2. Sao chép lần lượt `01_schema.sql`, `02_vocabulary.sql`, `03_internal_backend_sync.sql` và `04_sync_contract_v2.sql` dán vào.
3. Nhấn **Run** để khởi tạo bảng, indexes, RLS policies và contract đồng bộ của backend nội bộ.

## 3. Cấu hình Authentication
1. Mở **Authentication** -> **Providers**.
2. Bật **Google** (nhập Client ID & Secret từ Google Cloud Console).
3. Bật **Apple** (cho iOS app).
4. (Tùy chọn) Bật **Email / Password** để đăng ký trực tiếp.

## 4. Cấu hình vào Backend Server
Trong thư mục `server/`, tạo file `.env`:
```env
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_SERVICE_KEY=your-service-role-key-here
PORT=8000
```
