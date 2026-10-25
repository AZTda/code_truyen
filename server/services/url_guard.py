"""Kiểm tra an toàn cho mọi URL do client cung cấp trước khi máy chủ đi tải.

VÌ SAO CẦN:
Trước module này, `/v1/images/proxy` và `POST /v1/scrape` nhận URL tuỳ ý rồi tải về
mà không kiểm tra đích đến. Điều đó biến máy chủ thành công cụ SSRF: người ngoài có thể
đọc dịch vụ nội bộ (127.0.0.1), quét mạng LAN, đọc metadata máy ảo trên cloud, hoặc dùng
máy chủ làm proxy ẩn danh.

Module này chặn theo hai lớp:
  1. Danh sách tên miền cho phép (allowlist) — lớp chính
  2. Phân giải DNS rồi kiểm tra IP thật — chặn DNS rebinding và tên miền trỏ về nội bộ
"""

import ipaddress
import logging
import socket
from typing import Iterable, Optional
from urllib.parse import urlparse

logger = logging.getLogger("truyencloud.url_guard")


class UnsafeUrlError(ValueError):
    """URL bị từ chối vì lý do an toàn."""


# Tên miền được phép tải ảnh bìa. Chỉ liệt kê host thật sự phục vụ ảnh.
IMAGE_HOST_ALLOWLIST = {
    "cdn.cdnshu.com",
    "files.69shuba.com",
    "www.69shuba.com",
    "69shuba.com",
    "image.uukanshu.cc",
    "uukanshu.cc",
    "www.uukanshu.cc",
    "www.piaotia.com",
    "piaotia.com",
    "www.ptwxz.com",
}

# Tên miền được phép cào truyện.
SCRAPE_HOST_ALLOWLIST = {
    "69shuba.com", "www.69shuba.com", "69shuba-com.translate.goog",
    "uukanshu.cc", "www.uukanshu.cc", "uukanshu-cc.translate.goog",
    "piaotia.com", "www.piaotia.com", "ptwxz.com", "www.ptwxz.com",
    "twkan.com", "www.twkan.com",
    "html-duckduckgo-com.translate.goog",
}

# Các dải IP không bao giờ được phép làm đích đến
def _is_forbidden_ip(ip: ipaddress._BaseAddress) -> bool:
    return (
        ip.is_private          # 10/8, 172.16/12, 192.168/16, fc00::/7
        or ip.is_loopback      # 127/8, ::1
        or ip.is_link_local    # 169.254/16 (metadata cloud), fe80::/10
        or ip.is_reserved
        or ip.is_multicast
        or ip.is_unspecified
    )


def _resolve_and_check(host: str) -> None:
    """Phân giải tên miền và bảo đảm mọi IP trả về đều là địa chỉ công cộng."""
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror as e:
        raise UnsafeUrlError(f"Không phân giải được tên miền '{host}': {e}")

    if not infos:
        raise UnsafeUrlError(f"Tên miền '{host}' không có địa chỉ IP.")

    for info in infos:
        raw_ip = info[4][0]
        try:
            ip = ipaddress.ip_address(raw_ip)
        except ValueError:
            raise UnsafeUrlError(f"Địa chỉ IP không hợp lệ: {raw_ip}")
        if _is_forbidden_ip(ip):
            raise UnsafeUrlError(
                f"Tên miền '{host}' trỏ tới địa chỉ nội bộ ({raw_ip}) — từ chối."
            )


def assert_safe_url(
    url: str,
    allowlist: Optional[Iterable[str]] = None,
    *,
    require_allowlist: bool = True,
) -> str:
    """Kiểm tra URL an toàn, trả về URL đã chuẩn hoá. Ném UnsafeUrlError nếu không đạt.

    Args:
        url: URL do client cung cấp.
        allowlist: tập tên miền được phép. None = dùng IMAGE_HOST_ALLOWLIST.
        require_allowlist: False thì bỏ qua allowlist nhưng VẪN chặn IP nội bộ.
                           Chỉ dùng cho luồng quản trị đã xác thực.
    """
    clean = (url or "").strip()
    if not clean:
        raise UnsafeUrlError("URL rỗng.")
    if len(clean) > 2048:
        raise UnsafeUrlError("URL quá dài.")

    parsed = urlparse(clean)
    if parsed.scheme not in ("http", "https"):
        raise UnsafeUrlError(f"Chỉ chấp nhận http/https, nhận được '{parsed.scheme}'.")

    host = (parsed.hostname or "").lower().strip(".")
    if not host:
        raise UnsafeUrlError("URL không có tên miền.")

    # Chặn thẳng khi client đưa IP nội bộ dạng số
    try:
        ip = ipaddress.ip_address(host)
        if _is_forbidden_ip(ip):
            raise UnsafeUrlError(f"Địa chỉ nội bộ bị chặn: {host}")
    except ValueError:
        pass  # không phải IP dạng số, xử lý như tên miền

    if require_allowlist:
        hosts = set(allowlist) if allowlist is not None else IMAGE_HOST_ALLOWLIST
        if host not in hosts:
            raise UnsafeUrlError(f"Tên miền '{host}' không nằm trong danh sách cho phép.")

    _resolve_and_check(host)
    return clean


def is_safe_url(url: str, allowlist: Optional[Iterable[str]] = None, **kwargs) -> bool:
    try:
        assert_safe_url(url, allowlist, **kwargs)
        return True
    except UnsafeUrlError:
        return False


def assert_safe_redirect_chain(response) -> None:
    """Kiểm tra mọi chặng chuyển hướng của một phản hồi httpx.

    `follow_redirects=True` mở đường cho kiểu tấn công: tên miền hợp lệ trả 302
    trỏ về 127.0.0.1. Hàm này soát lại toàn bộ lịch sử sau khi tải xong.
    """
    for hop in list(getattr(response, "history", [])) + [response]:
        host = (hop.url.host or "").lower()
        if not host:
            continue
        try:
            ip = ipaddress.ip_address(host)
            if _is_forbidden_ip(ip):
                raise UnsafeUrlError(f"Chuyển hướng tới địa chỉ nội bộ: {hop.url}")
            continue
        except ValueError:
            pass
        _resolve_and_check(host)
