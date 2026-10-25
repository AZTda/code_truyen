"""Phân loại đường dẫn /v1/ vào nhóm giới hạn tần suất (xem services/rate_limit.py cho các xô).

Tách riêng để rate_limit.py chỉ còn xô token + middleware; `rate_limit.group_for_path` vẫn dùng được.
"""

import re
from urllib.parse import parse_qsl

# Tóm tắt chương và cào-lại-rồi-dịch là lời gọi AI đầy đủ dù nằm dưới /v1/novels/
_AI_CHAPTER_ACTION = re.compile(r"^/v1/novels/[^/]+/chapters/[^/]+/(recap|recrawl)/?$")

# GET chương kèm ?recrawl=<đúng> ép cào lại trang nguồn (có thể kèm dịch AI) dù không phải /recrawl
_CHAPTER_GET = re.compile(r"^/v1/novels/[^/]+/chapters/[^/]+/?$")
# Giá trị mà bộ phân tích bool của FastAPI/pydantic coi là đúng (không phân biệt hoa thường)
_TRUTHY = frozenset({"1", "true", "t", "yes", "y", "on"})

# Nạp tệp, tìm kiếm và cào truyện khớp ĐÚNG đường dẫn: id truyện nạp từ tệp là `import_<hex>`, nên khớp theo
# tiền tố sẽ nhốt cả trang chi tiết/chương của truyện đã nạp vào xô "scrape" (10 yêu cầu/phút mỗi IP).
_SCRAPE_EXACT = re.compile(r"^/v1/(?:novels/(?:import|search)|scrape)/?$")
# Kiểm tra chương mới của MỘT truyện cào lại mục lục nguồn và có thể dịch trước 2 chương
_NOVEL_CHECK_UPDATES = re.compile(r"^/v1/novels/[^/]+/check-updates/?$")

# Route chỉ dành cho quản trị nằm ngoài /v1/admin/: dùng chung xô "admin" với trang và API quản trị
_ADMIN_GUARDED_ANY_METHOD = frozenset({
    "/v1/app/version", "/v1/settings/models/refresh", "/v1/settings/models/select", "/v1/health/deep",
    "/v1/english/cache",  # DELETE xoá cache dùng chung; /v1/english/cache/stats là đường dẫn khác
})
_ADMIN_GUARDED_POST = frozenset({"/v1/settings/profile"})  # GET cùng đường dẫn là đọc công khai

# Ánh xạ tiền tố đường dẫn sang nhóm giới hạn
_PATH_GROUPS = (
    ("/v1/english/pronunciation/", "pronunciation"),
    ("/v1/english/", "ai"),
    ("/v1/translate", "ai"),
    ("/v1/batch-translate", "ai"),
    ("/v1/lore/", "ai"),
    ("/v1/companion/", "ai"),
    ("/v1/images/", "image"),
    ("/v1/tts/prefetch", "tts_prefetch"),
    # Hỏi tiến độ dựng giọng (app gọi lại mỗi vài giây trong lúc chờ): xô riêng, không tranh với ảnh bìa
    ("/v1/tts/prerender", "tts_prerender"),
    # Luồng giọng đọc có xô RIÊNG: một lần 429 làm app tưởng giọng AI hỏng và chuyển cả phiên sang giọng máy ~3 phút
    ("/v1/tts/stream", "tts_stream"),
    ("/v1/tts/", "image"),
    ("/v1/auth/", "auth"),
    # Trang quản trị gọi nhiều API mỗi lần mở: xô riêng, rộng hơn xô đăng nhập (đăng nhập vẫn ở "auth")
    ("/v1/admin/", "admin"),
    ("/admin", "admin"),
)


def wants_recrawl(query: str) -> bool:
    """`recrawl` trong query có được FastAPI hiểu là đúng không (on/t/y/%74rue... đều tính, y như route)."""
    return any(
        key == "recrawl" and value.strip().lower() in _TRUTHY
        for key, value in parse_qsl(query, keep_blank_values=True)
    )


def group_for_path(path: str, query: str = "", method: str = "GET") -> str:
    if _AI_CHAPTER_ACTION.match(path):
        return "ai"
    if query and _CHAPTER_GET.match(path) and wants_recrawl(query):
        return "ai"
    if _SCRAPE_EXACT.match(path) or _NOVEL_CHECK_UPDATES.match(path):
        return "scrape"
    trimmed = path.rstrip("/")
    if trimmed in _ADMIN_GUARDED_ANY_METHOD or (method.upper() == "POST" and trimmed in _ADMIN_GUARDED_POST):
        return "admin"
    for prefix, group in _PATH_GROUPS:
        if path.startswith(prefix):
            return group
    return "default"
