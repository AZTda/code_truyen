# TruyenCloud 2026 — Thin Client + Fat Backend

> **1 Codebase Compose Multiplatform** → Android & iOS có giao diện giống nhau 100%.  
> **Fat Backend** xử lý toàn bộ tác vụ nặng (cào truyện, dịch Antigravity AI, tìm kiếm).  
> **$0 Chi phí API** nhờ tận dụng gói Antigravity bản quyền đã mua.  
> **1 File = 1 Chức năng**, mã nguồn gọn gàng, tối đa dưới 1000 dòng/file để dễ bảo trì trong 5 năm tới.

---

## 1. Cấu Trúc Dự Án

```
TruyenCloud/
├── server/                       # 🖥️ Fat Backend (Python 3.13 + FastAPI)
│   ├── server.py                 # FastAPI Gateway & Middleware
│   ├── config.py                 # Cấu hình môi trường & timeout
│   ├── database.py               # Supabase Client & InMemory Mock Store
│   ├── models/                   # Pydantic DTOs & Models
│   │   ├── novel.py
│   │   ├── chapter.py
│   │   └── translate.py
│   ├── services/                 # Bộ não xử lý cào & dịch truyện
│   │   ├── translation_worker.py # Dịch truyện bằng Google Antigravity SDK
│   │   ├── scraper_registry.py   # Điều phối scraper theo domain
│   │   ├── scraper_69shuba.py    # Parser 69shuba (Bypass Google Translate Proxy + GBK)
│   │   ├── scraper_piaotia.py    # Parser Piaotia (GBK encoding)
│   │   ├── scraper_uukanshu.py   # Parser UuKanshu (Proxy bypass)
│   │   ├── source_policy.py      # Gỡ nguồn không miễn phí (Tấn Giang)
│   │   └── search_service.py     # Tìm kiếm truyện đa nguồn song song
│   ├── routes/                   # API Endpoints /v1/
│   │   ├── health.py             # GET /v1/health
│   │   ├── novels.py             # GET /v1/novels/search, /v1/novels/{id}
│   │   ├── chapters.py           # GET /v1/novels/{id}/chapters/{num}
│   │   ├── translate.py          # POST /v1/translate, /v1/batch-translate
│   │   └── scrape.py             # POST /v1/scrape
│   ├── tests/                    # Kiểm thử tự động (pytest)
│   └── run_server.sh             # Script khởi chạy server một chạm trên macOS
│
├── supabase/                     # ☁️ Database & Đồng bộ Cloud
│   ├── 01_schema.sql             # Toàn bộ SQL Schema + RLS + Realtime
│   └── README.md                 # Hướng dẫn thiết lập Supabase
│
└── app/                          # 📱 Thin Client (Compose Multiplatform)
    ├── gradle/libs.versions.toml # Catalog thư viện 2026 (Kotlin 2.4, CMP 1.8)
    ├── shared/                   # Logic mỏng: Room KMP, Ktor API, Supabase Auth
    ├── composeApp/               # 🎨 Giao diện MỚI 100%
    │   ├── theme/                # Design System (Color, Type, Shape, Spacing, Animation)
    │   ├── components/           # 11 Component tái sử dụng (NovelCard, GlassCard,...)
    │   ├── screens/              # Màn hình: Auth, Home, Detail, Reader, Settings
    │   └── navigation/AppNav.kt  # Điều hướng toàn ứng dụng
    ├── androidApp/               # Vỏ ứng dụng Android
    └── iosApp/                   # Vỏ ứng dụng iOS (SwiftUI wrapper)
```

---

## 2. Kiểm Thử & Chạy Server Trên macOS

### Chạy kiểm thử tự động:
```bash
cd "TruyenCloud/server"
./.venv/bin/pytest tests/
```
*(Kết quả: 7/7 tests passed)*

### Khởi động Server:
```bash
cd "TruyenCloud/server"
./run_server.sh
```
Server sẽ lắng nghe tại: `http://127.0.0.1:8000`

### Kiểm tra trạng thái:
```bash
curl http://127.0.0.1:8000/v1/health
```
Phản hồi:
```json
{
  "status": "healthy",
  "service": "TruyenCloud Backend API",
  "version": "1.0.0",
  "python_version": "3.13.15",
  "platform": "macOS-...",
  "database": "mock_memory",
  "engine": "Antigravity SDK (2026)"
}
```

---

## 3. Triển Khai Lên Ubuntu 26 (Khi sẵn sàng)

Khi chuyển sang máy chủ Ubuntu 26, chỉ cần copy thư mục `server/` lên server và chạy:

1. **Cài đặt Python 3.13 & uv:**
   ```bash
   curl -LsSf https://astral.sh/uv/install.sh | sh
   source $HOME/.local/bin/env
   uv venv --python 3.13 /opt/truyencloud/.venv
   uv pip install --python /opt/truyencloud/.venv/bin/python -r requirements.txt
   ```

2. **Tạo service systemd (`/etc/systemd/system/truyencloud.service`):**
   ```ini
   [Unit]
   Description=TruyenCloud API Gateway
   After=network.target

   [Service]
   Type=exec
   User=root
   WorkingDirectory=/opt/truyencloud
   ExecStart=/opt/truyencloud/.venv/bin/uvicorn server:app --host 0.0.0.0 --port 8000 --workers 4
   Restart=always
   RestartSec=5

   [Install]
   WantedBy=multi-user.target
   ```

3. **Kích hoạt:**
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now truyencloud
   ```
