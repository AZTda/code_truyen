"""Giới hạn kích thước body request, áp ở tầng ASGI trước khi FastAPI đọc và parse.

VÌ SAO CẦN: FastAPI/uvicorn gom cả body vào RAM rồi mới kiểm tra từng trường, nên một POST 100 MB
không xác thực vào /v1/tts/prefetch (mở cho khách) hoặc /v1/auth/login làm máy chủ cấp phát hàng trăm MB
trước khi trả lời 413. Bộ giới hạn từ chối ngay theo Content-Length (không đọc body) và cắt luồng chunked
khi đã nhận quá trần.
"""

import logging
from typing import Dict, Optional, Tuple

# Phải là HTTPException của FastAPI: FastAPI chỉ cho lớp này đi qua khi đọc body, còn HTTPException của
# Starlette bị nó đổi thành "400 There was an error parsing the body".
from fastapi import HTTPException
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

logger = logging.getLogger("truyencloud.body_limit")

_KIB = 1024
_MIB = 1024 * 1024

# Trần mặc định cho mọi route: JSON thường của app chỉ vài KB.
DEFAULT_MAX_BODY_BYTES = 256 * _KIB

# Đường dẫn khớp ĐÚNG -> trần riêng. Mỗi mục là một payload hợp lệ lớn hơn trần mặc định.
EXACT_LIMITS: Dict[str, int] = {
    # Tệp truyện <= 25 MB (route tự chặn khi đọc, trả 413 kèm thông báo) + phần bao multipart.
    "/v1/novels/import": 26 * _MIB,
    # App đẩy lô 200 từ + 1000 log mỗi request; mỗi từ kèm định nghĩa, ngữ cảnh, truyện ghi nhớ... nên vượt 256 KB.
    "/v1/vocabulary/sync": 4 * _MIB,
    # Lô 500 bookmark, mỗi cái có thể mang tới ~28 KB văn bản chọn + ghi chú.
    "/v1/bookmarks/sync": 2 * _MIB,
    # `content` tối đa 500.000 ký tự (~1,5 MB UTF-8).
    "/v1/translate": 2 * _MIB,
}
# Nhóm route nhận nguyên cả chương hoặc cả danh sách từ đã thuộc (progressive-chapter gửi mọi từ đã thuộc).
PREFIX_LIMITS: Tuple[Tuple[str, int], ...] = (
    ("/v1/english/", 2 * _MIB),
)


def max_body_bytes(path: str) -> int:
    """Trần body (byte) cho một đường dẫn."""
    exact = EXACT_LIMITS.get(path.rstrip("/") or "/")
    if exact is not None:
        return exact
    for prefix, limit in PREFIX_LIMITS:
        if path.startswith(prefix):
            return limit
    return DEFAULT_MAX_BODY_BYTES


def _declared_length(scope: Scope) -> Optional[int]:
    for name, value in scope.get("headers", ()):
        if name == b"content-length":
            try:
                return int(value)
            except ValueError:
                return None
    return None


def _too_large_detail(limit: int) -> str:
    return f"Nội dung yêu cầu quá lớn (tối đa {limit // _KIB} KB cho đường dẫn này)."


class BodySizeLimitMiddleware:
    """Middleware ASGI thuần (không BaseHTTPMiddleware) để chen vào được luồng `receive`."""

    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        limit = max_body_bytes(scope.get("path", ""))
        declared = _declared_length(scope)
        if declared is not None and declared > limit:
            logger.warning("Từ chối body %d byte > %d tại %s", declared, limit, scope.get("path"))
            # Không đọc body; Connection: close để uvicorn đóng kết nối thay vì chờ phần còn lại.
            response = JSONResponse(
                {"detail": _too_large_detail(limit)}, status_code=413, headers={"Connection": "close"}
            )
            await response(scope, receive, send)
            return

        received = 0

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    # Body chunked (không có Content-Length) vượt trần: ngắt luồng -> ExceptionMiddleware trả 413.
                    raise HTTPException(status_code=413, detail=_too_large_detail(limit),
                                        headers={"Connection": "close"})
            return message

        await self.app(scope, limited_receive, send)
