"""Trang web quản trị /admin: phục vụ static/admin.html kèm Content-Security-Policy chặt.

Trang giữ khoá quản trị trong sessionStorage nên một chỗ chèn được mã (XSS) là mất khoá. CSP ở đây là lớp
phòng thủ thứ hai sau việc thoát ký tự trong admin.html: script chỉ chạy khi mang nonce do server cấp cho
đúng lượt trả lời này, nên thuộc tính sự kiện inline (onerror=..., onclick=...) bị trình duyệt chặn dù có lọt vào DOM.
"""

import secrets
from pathlib import Path
from typing import Dict

from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse

router = APIRouter(tags=["Admin"])

ADMIN_STATIC_PATH = Path(__file__).parent.parent / "static" / "admin.html"
# admin.html giữ chỗ này trong thẻ <script nonce="...">; mở thẳng tệp (file://) thì nó vô hại.
NONCE_PLACEHOLDER = "__CSP_NONCE__"
# Khi mở từ localhost / file://, trang tự gọi API của máy chủ thật này (xem DEFAULT_API_BASE trong admin.html).
ADMIN_API_ORIGIN = "https://truyen.achatvn.com"


def content_security_policy(nonce: str) -> str:
    return "; ".join([
        "default-src 'none'",
        f"script-src 'nonce-{nonce}'",
        # Trang dùng <style> và style="..." inline; kiểu dáng inline không thực thi mã.
        "style-src 'unsafe-inline' https://fonts.googleapis.com",
        "font-src https://fonts.gstatic.com",
        # Ảnh đại diện là URL http(s) bất kỳ do quản trị nhập; scheme khác đã bị admin.html lọc.
        "img-src 'self' data: https: http:",
        f"connect-src 'self' {ADMIN_API_ORIGIN}",
        "base-uri 'none'",
        "form-action 'none'",
        "frame-ancestors 'none'",
    ])


def security_headers(nonce: str) -> Dict[str, str]:
    return {
        "Content-Security-Policy": content_security_policy(nonce),
        "X-Content-Type-Options": "nosniff",
        "X-Frame-Options": "DENY",
        "Referrer-Policy": "no-referrer",
        # Nonce đổi theo từng lượt nên bản lưu đệm sẽ không còn khớp.
        "Cache-Control": "no-store",
    }


@router.get("/admin", response_class=HTMLResponse)
async def get_admin_page():
    """Phục vụ giao diện trang web quản trị tài khoản TruyenCloud."""
    if not ADMIN_STATIC_PATH.exists():
        raise HTTPException(status_code=404, detail="Trang quản trị chưa được cài đặt.")
    html = ADMIN_STATIC_PATH.read_text(encoding="utf-8")
    nonce = secrets.token_urlsafe(18)
    return HTMLResponse(
        content=html.replace(NONCE_PLACEHOLDER, nonce),
        status_code=200,
        headers=security_headers(nonce),
    )
