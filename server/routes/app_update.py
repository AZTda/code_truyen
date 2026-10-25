import os
import re
import json
import hashlib
import logging
import tempfile
from typing import Optional
from urllib.parse import urlsplit
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import RedirectResponse, FileResponse
from pydantic import BaseModel
from config import settings
from routes.admin_access import verify_admin_access

logger = logging.getLogger("truyencloud.app_update")

router = APIRouter(prefix="/app", tags=["App Update"])

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(BASE_DIR, "data")
STATIC_DIR = os.path.join(BASE_DIR, "static")
VERSION_FILE = os.path.join(DATA_DIR, "app_version.json")
STATIC_APK_PATH = os.path.join(STATIC_DIR, "truyencloud-latest.apk")

SUPPORTED_PLATFORMS = ("android", "ios")
_SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")
# download_url hợp lệ chỉ gồm ASCII in được, không khoảng trắng và không gạch chéo ngược. Ký tự điều khiển (NUL...),
# dấu cách, ký tự Unicode lạ (／, U+2100, RTL override) trước đây làm urlsplit/realpath ném ValueError -> HTTP 500.
_UNSAFE_URL_CHARS = re.compile(r"[^\x21-\x7e]|\\")

DEFAULT_VERSION_DATA = {
    "version_code": 1,
    "version_name": "1.0.0",
    "min_version_code": 1,
    "download_url": "/static/truyencloud-latest.apk",
    "file_size_bytes": 0,
    "changelog": "- Khởi tạo TruyenCloud 2026\n- Hệ thống tra cứu từ vựng và dịch AI",
    "release_date": "2026-09-23",
    "is_mandatory": False,
    "sha256": "",
}

# APK chỉ dành cho Android: iOS luôn nhận "không có bản cập nhật" (version_code 0)
NO_UPDATE_DATA = {
    "version_code": 0,
    "version_name": "",
    "min_version_code": 0,
    "download_url": "",
    "file_size_bytes": 0,
    "changelog": "",
    "release_date": "",
    "is_mandatory": False,
    "sha256": "",
    "apk_exists": False,
}


def _load_version_data() -> dict:
    os.makedirs(DATA_DIR, exist_ok=True)
    if os.path.exists(VERSION_FILE):
        try:
            with open(VERSION_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                merged = {**DEFAULT_VERSION_DATA, **data}
                return merged
        except Exception as e:
            logger.warning(f"Lỗi đọc {VERSION_FILE}: {e}")

    try:
        with open(VERSION_FILE, "w", encoding="utf-8") as f:
            json.dump(DEFAULT_VERSION_DATA, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.warning(f"Lỗi ghi {VERSION_FILE}: {e}")

    return DEFAULT_VERSION_DATA.copy()


def _save_version_data(data: dict):
    os.makedirs(DATA_DIR, exist_ok=True)
    fd, temp_path = tempfile.mkstemp(prefix=".app_version-", suffix=".tmp", dir=DATA_DIR)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.replace(temp_path, VERSION_FILE)
    finally:
        if os.path.exists(temp_path):
            os.unlink(temp_path)


def _origin(url: str) -> Optional[tuple]:
    try:
        parts = urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
            return None
        port = parts.port or (443 if parts.scheme == "https" else 80)
    except ValueError:  # "http://[::1/x" (ngoặc IPv6 hở), cổng sai, host không chuẩn hoá được...
        return None
    return parts.scheme, parts.hostname, port


def _is_own_download_url(url: str) -> bool:
    """Chỉ nhận APK trên chính máy chủ này: đường dẫn /static/ tương đối, hoặc URL
    tuyệt đối cùng origin với BASE_URL. Chặn việc trỏ bản cập nhật bắt buộc ra ngoài.
    Giá trị dị dạng trả False (route đáp 400), không bao giờ ném ngoại lệ."""
    if not url or _UNSAFE_URL_CHARS.search(url) or ".." in url:
        return False
    try:
        parts = urlsplit(url)
    except ValueError:
        return False
    if not parts.scheme and not parts.netloc:
        return parts.path.startswith("/static/")
    own = _origin(settings.BASE_URL)
    return own is not None and _origin(url) == own


def _local_static_file(url: str) -> Optional[str]:
    """Tệp trong thư mục static mà download_url trỏ tới (nếu có) — để tự tính sha256."""
    try:
        path = urlsplit(url).path
        if not path.startswith("/static/"):
            return None
        candidate = os.path.realpath(os.path.join(STATIC_DIR, path[len("/static/"):]))
    except (ValueError, OSError):  # NUL trong đường dẫn: realpath ném ValueError
        return None
    if not candidate.startswith(os.path.realpath(STATIC_DIR) + os.sep) or not os.path.isfile(candidate):
        return None
    return candidate


def _sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class AppVersionUpdateRequest(BaseModel):
    version_code: int
    version_name: str
    changelog: str
    min_version_code: Optional[int] = 1
    download_url: Optional[str] = None
    is_mandatory: Optional[bool] = False
    release_date: Optional[str] = None
    sha256: Optional[str] = None


@router.api_route("/check-update", methods=["GET", "HEAD"])
async def check_update(platform: str = Query("android", max_length=16)):
    """
    Kiểm tra phiên bản ứng dụng mới nhất cho Android.
    Trả về thông tin phiên bản, link tải, dung lượng và sha256 của file APK.
    """
    platform = platform.strip().lower()
    if platform not in SUPPORTED_PLATFORMS:
        raise HTTPException(status_code=400, detail="platform chỉ nhận 'android' hoặc 'ios'.")
    if platform == "ios":
        return {**NO_UPDATE_DATA, "platform": "ios"}

    info = _load_version_data()
    info["sha256"] = str(info.get("sha256") or "")
    info["platform"] = "android"

    if os.path.exists(STATIC_APK_PATH):
        try:
            info["file_size_bytes"] = os.path.getsize(STATIC_APK_PATH)
            info["apk_exists"] = True
        except Exception:
            info["apk_exists"] = False
    else:
        info["apk_exists"] = False

    return info


@router.api_route("/download-latest", methods=["GET", "HEAD"])
async def download_latest():
    """
    Tải trực tiếp bản APK mới nhất của TruyenCloud.
    """
    if os.path.exists(STATIC_APK_PATH):
        return FileResponse(
            STATIC_APK_PATH,
            media_type="application/vnd.android.package-archive",
            filename="truyencloud-latest.apk",
        )
    info = _load_version_data()
    url = info.get("download_url") or "/static/truyencloud-latest.apk"
    return RedirectResponse(url=url)


@router.post("/version")
async def update_version_info(
    req: AppVersionUpdateRequest,
    _: bool = Depends(verify_admin_access),
):
    """
    Cập nhật thông tin phiên bản mới (chỉ quản trị viên).
    """
    download_url = (req.download_url or "").strip()
    if download_url and not _is_own_download_url(download_url):
        raise HTTPException(
            status_code=400,
            detail="download_url phải là đường dẫn /static/ hoặc nằm trên chính máy chủ (BASE_URL).",
        )
    sha256 = (req.sha256 or "").strip().lower()
    if sha256 and not _SHA256_HEX.match(sha256):
        raise HTTPException(status_code=400, detail="sha256 phải là chuỗi hex 64 ký tự.")

    info = _load_version_data()
    info["version_code"] = req.version_code
    info["version_name"] = req.version_name
    info["changelog"] = req.changelog
    if req.min_version_code is not None:
        info["min_version_code"] = req.min_version_code
    if download_url:
        info["download_url"] = download_url
    if req.is_mandatory is not None:
        info["is_mandatory"] = req.is_mandatory
    if req.release_date:
        info["release_date"] = req.release_date

    if not sha256:
        # Không gửi sha256 thì băm chính tệp sẽ được tải; không để lại mã băm của bản cũ
        local_file = _local_static_file(info.get("download_url") or "")
        sha256 = _sha256_of(local_file) if local_file else ""
    info["sha256"] = sha256

    _save_version_data(info)
    return {"message": "Cập nhật thông tin phiên bản thành công", "version": info}
