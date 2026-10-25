"""Trả tệp MP3 TTS có hỗ trợ HTTP Range (206) và header thời lượng, không phụ thuộc phiên bản Starlette.

App tua chính xác theo thời gian (nghe lại đúng chữ đang đọc), nên trình phát cần Range để seek mà không tải lại cả
tệp: `Accept-Ranges: bytes`, `206 Partial Content` + `Content-Range`, `416` khi vượt quá. Tệp MP3 nhỏ (vài trăm KB) và
CBR 64 kbps nên đọc cả tệp vào bộ nhớ rồi cắt là đủ. Nhiều khoảng trong một yêu cầu thì trả cả tệp (RFC 9110 cho phép).
"""

import re
from typing import Dict, Optional, Tuple

from fastapi import Response

from services import tts_cache

CACHE_CONTROL = "public, max-age=604800"
_RANGE = re.compile(r"^\s*bytes\s*=\s*(\d*)\s*-\s*(\d*)\s*$", re.IGNORECASE)
EXPOSE = "X-Audio-Duration-Ms, Content-Range, Accept-Ranges, Content-Length"


def parse_range(header: Optional[str], size: int) -> Optional[Tuple[int, int]]:
    """(đầu, cuối) bao gồm cả hai đầu; None nếu không có/không hiểu/nhiều khoảng (-> trả cả tệp).

    Ném ValueError khi khoảng hợp lệ về cú pháp nhưng không thoả được (-> 416).
    """
    if not header or "," in header:
        return None
    match = _RANGE.match(header)
    if not match:
        return None
    first, last = match.group(1), match.group(2)
    if not first and not last:
        return None
    if not first:  # bytes=-N: N byte cuối
        length = int(last)
        if length <= 0:
            raise ValueError("unsatisfiable")
        return max(0, size - length), size - 1
    start = int(first)
    end = int(last) if last else size - 1
    if start >= size or (last and end < start):
        raise ValueError("unsatisfiable")
    return start, min(end, size - 1)


def audio_response(mp3_path: str, md5: str, duration_ms: int, range_header: Optional[str] = None,
                   if_range: Optional[str] = None, extra: Optional[Dict[str, str]] = None) -> Response:
    with open(mp3_path, "rb") as handle:
        data = handle.read()
    size = len(data)
    etag = f'"{md5}"'
    headers = {
        "Accept-Ranges": "bytes",
        "Cache-Control": CACHE_CONTROL,
        "ETag": etag,
        "X-Audio-Duration-Ms": str(int(duration_ms)),
        "Content-Disposition": f'inline; filename="tts_{md5}.mp3"',
        "Access-Control-Expose-Headers": EXPOSE,
    }
    headers.update(extra or {})

    use_range = range_header and (not if_range or if_range.strip() == etag)
    if use_range:
        try:
            span = parse_range(range_header, size)
        except ValueError:
            headers["Content-Range"] = f"bytes */{size}"
            return Response(status_code=416, headers=headers)
        if span is not None:
            start, end = span
            headers["Content-Range"] = f"bytes {start}-{end}/{size}"
            return Response(content=data[start:end + 1], status_code=206, media_type="audio/mpeg", headers=headers)
    return Response(content=data, status_code=200, media_type="audio/mpeg", headers=headers)


def touch_and_duration(mp3_path: str) -> int:
    tts_cache.touch(mp3_path)
    return tts_cache.read_duration_ms(mp3_path)
