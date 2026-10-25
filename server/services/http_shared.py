"""Shared httpx.AsyncClient for scrapers and cover lookups.

Trước đây mỗi lời gọi scraper/tra bìa tạo một AsyncClient mới (bắt tay TCP+TLS
lại từ đầu). Client dùng chung giữ keep-alive connection pool nên lượt tìm kiếm
thứ hai trở đi tiết kiệm 100-300ms mỗi nguồn. Đóng tại lifespan shutdown.
"""

import httpx
from typing import Optional
from config import settings

_client: Optional[httpx.AsyncClient] = None


def get_shared_client() -> httpx.AsyncClient:
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(
            timeout=settings.SCRAPER_TIMEOUT_SECONDS,
            follow_redirects=True,
            limits=httpx.Limits(max_connections=20, max_keepalive_connections=10),
        )
    return _client


async def close_shared_client() -> None:
    global _client
    if _client is not None and not _client.is_closed:
        await _client.aclose()
    _client = None
