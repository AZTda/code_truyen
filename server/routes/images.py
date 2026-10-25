import os
import hashlib
import logging
import urllib.parse
import httpx
from fastapi import APIRouter, Query
from fastapi.responses import Response
from services.url_guard import assert_safe_url, UnsafeUrlError

logger = logging.getLogger("truyencloud.images")

router = APIRouter(prefix="/images", tags=["Images"])

CACHE_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "covers_cache")
os.makedirs(CACHE_DIR, exist_ok=True)

# Anh bia khong bao gio lon hon muc nay; chan de tranh can kiet bo nho
MAX_IMAGE_BYTES = 8 * 1024 * 1024

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36",
    "Accept": "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
}

import re

STATIC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static")
FALLBACK_JPEG_PATH = os.path.join(STATIC_DIR, "fallback_cover.jpg")
FALLBACK_JPEG_BYTES = b""
if os.path.exists(FALLBACK_JPEG_PATH):
    with open(FALLBACK_JPEG_PATH, "rb") as f:
        FALLBACK_JPEG_BYTES = f.read()

def get_fallback_response():
    if FALLBACK_JPEG_BYTES:
        return Response(
            content=FALLBACK_JPEG_BYTES,
            media_type="image/jpeg",
            headers={"Cache-Control": "public, max-age=604800"}
        )
    return Response(status_code=404)

@router.api_route("/proxy", methods=["GET", "HEAD"])
async def proxy_image(url: str = Query(..., description="Target image URL to proxy")):
    """Proxies novel cover images with local disk caching to bypass hotlink protection (403 Forbidden)."""
    clean_url = url.strip()
    if not clean_url:
        return get_fallback_response()

    # Handle local static paths directly
    if clean_url.startswith("/static/") or clean_url.startswith("static/"):
        filename = clean_url.split("/")[-1]
        local_file = os.path.join(STATIC_DIR, filename)
        if os.path.exists(local_file):
            with open(local_file, "rb") as f:
                return Response(content=f.read(), media_type="image/jpeg", headers={"Cache-Control": "public, max-age=604800"})
        return get_fallback_response()

    # Heal legacy 69shuba image domain
    m69 = re.search(r"article/image/(\d+)/(\d+)/(\d+)s?\.jpg", clean_url)
    if m69 and ("69shu" in clean_url or "cdnshu" in clean_url or "files.69shuba" in clean_url):
        p, bid, _ = m69.groups()
        clean_url = f"https://cdn.cdnshu.com/files/article/image/{p}/{bid}/{bid}s.jpg"
    elif "files.69shuba.com" in clean_url:
        clean_url = re.sub(r"https?://files\.69shuba\.com(?:/files)?", "https://cdn.cdnshu.com/files", clean_url)

    # Heal UUKanshu cover domain
    muu = re.search(r"article/image/(\d+)/(\d+)/(\d+)s?\.jpg", clean_url)
    if muu and "uukanshu" in clean_url:
        p, bid, _ = muu.groups()
        clean_url = f"https://image.uukanshu.cc/{p}/{bid}/{bid}s.jpg"

    # Check local disk cache first
    url_hash = hashlib.md5(clean_url.encode("utf-8")).hexdigest()
    cached_path = os.path.join(CACHE_DIR, f"{url_hash}.img")
    cached_meta_path = os.path.join(CACHE_DIR, f"{url_hash}.meta")

    if os.path.exists(cached_path) and os.path.getsize(cached_path) > 500:
        try:
            content_type = "image/jpeg"
            if os.path.exists(cached_meta_path):
                with open(cached_meta_path, "r", encoding="utf-8") as f:
                    content_type = f.read().strip() or "image/jpeg"
            with open(cached_path, "rb") as f:
                content = f.read()
            return Response(
                content=content,
                media_type=content_type,
                headers={"Cache-Control": "public, max-age=604800"}
            )
        except Exception:
            pass

    # Set appropriate Referer header
    req_headers = dict(HEADERS)
    parsed = urllib.parse.urlparse(clean_url)
    domain = parsed.netloc.lower()

    if "cdnshu" in domain or "69shuba" in domain or "69shu" in domain:
        req_headers["Referer"] = "https://www.69shuba.com/"
    elif "piaotia" in domain or "ptwxz" in domain:
        req_headers["Referer"] = "https://www.piaotia.com/"
    elif "uukanshu" in domain:
        req_headers["Referer"] = "https://uukanshu.cc/"
    elif "qidian" in domain:
        req_headers["Referer"] = "https://www.qidian.com/"
    elif "faloo" in domain:
        req_headers["Referer"] = "https://b.faloo.com/"
    elif "zongheng" in domain:
        req_headers["Referer"] = "https://www.zongheng.com/"
    elif "17k" in domain:
        req_headers["Referer"] = "https://www.17k.com/"
    elif domain:
        req_headers["Referer"] = f"{parsed.scheme}://{parsed.netloc}/"

    # CHAN SSRF: chi cho phep tai anh tu cac ten mien da duyet, va chan moi
    # dia chi noi bo (127.x, 10.x, 192.168.x, 169.254.x metadata cloud...).
    # Truoc ban va nay, `?url=http://127.0.0.1:8000/v1/settings` tra ve nguyen
    # cau hinh may chu cho bat ky ai goi.
    try:
        assert_safe_url(clean_url)
    except UnsafeUrlError as e:
        logger.warning("Tu choi proxy anh: %s", e)
        return get_fallback_response()

    try:
        async with httpx.AsyncClient(timeout=15.0, follow_redirects=False) as client:
            current_url = clean_url
            body = b""
            content_type = ""
            for _ in range(4):
                # Validate every target before making the next network request.
                # A valid image host must not be allowed to redirect into a LAN host.
                assert_safe_url(current_url)
                request = client.build_request("GET", current_url, headers=req_headers)
                resp = await client.send(request, stream=True, follow_redirects=False)
                try:
                    if 300 <= resp.status_code < 400:
                        location = resp.headers.get("location")
                        if not location:
                            return get_fallback_response()
                        current_url = urllib.parse.urljoin(current_url, location)
                        continue

                    resp.raise_for_status()
                    content_type = resp.headers.get("content-type", "")
                    if not content_type.lower().startswith("image/"):
                        logger.warning(
                            "Tu choi proxy: content-type khong phai anh (%s) cho %s",
                            content_type, current_url[:120]
                        )
                        return get_fallback_response()

                    chunks = []
                    total = 0
                    async for chunk in resp.aiter_bytes():
                        total += len(chunk)
                        if total > MAX_IMAGE_BYTES:
                            logger.warning("Anh vuot %d byte, bo qua: %s", MAX_IMAGE_BYTES, current_url[:120])
                            return get_fallback_response()
                        chunks.append(chunk)
                    body = b"".join(chunks)
                    break
                finally:
                    await resp.aclose()
            else:
                logger.warning("Qua so lan chuyen huong cho anh: %s", clean_url[:120])
                return get_fallback_response()

        if len(body) > 500:
            try:
                with open(cached_path, "wb") as f:
                    f.write(body)
                with open(cached_meta_path, "w", encoding="utf-8") as f:
                    f.write(content_type)
            except Exception:
                pass

        return Response(
            content=body,
            media_type=content_type,
            headers={"Cache-Control": "public, max-age=604800"}
        )
    except UnsafeUrlError as e:
        logger.warning("Chuyen huong khong an toan: %s", e)
        return get_fallback_response()
    except Exception:
        # Fallback to high-quality TruyenCloud embossed hardcover book image
        return get_fallback_response()
