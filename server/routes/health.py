from fastapi import APIRouter, Depends
from database import db
from routes.admin import verify_admin_access

router = APIRouter(tags=["Health"])

API_VERSION = "1.0.0"
# Một dòng cố định được ghi đè mỗi lần kiểm tra, thay vì sinh một dòng mới mỗi lần gọi
_DEEP_PING_KEY = "deep_health_ping"


@router.get("/health")
async def health_check():
    """Health check công khai: cố ý không lộ phiên bản Python, hệ điều hành hay loại CSDL."""
    return {"status": "healthy", "version": API_VERSION}


@router.get("/health/cache")
async def cache_usage():
    """Dung lượng THẬT của các thư mục đệm trên máy chủ.

    Thay cho con số cứng "24.5 MB" mà ứng dụng vẫn hiển thị ở màn hình Cài Đặt.
    """
    from services.cache_janitor import usage_report, cache_janitor
    return {
        "directories": usage_report(),
        "janitor_running": cache_janitor.is_running,
        "last_sweep": cache_janitor.last_run,
        "last_result": cache_janitor.last_result,
    }


@router.get("/health/titles")
async def titles_health(_: bool = Depends(verify_admin_access)):
    """Trạng thái dịch tựa đề không Google: từ điển convert, dịch vụ Hy-MT2, hàng đợi tinh chỉnh, cache. Chỉ quản trị."""
    from services.title_cache_store import title_cache
    from services.title_dict import title_dict
    from services.title_mt_client import title_mt
    from services.title_refiner import title_refiner
    healthy = await title_mt.healthy(max_age=0)
    return {
        "dictionary": title_dict.status(),
        "mt": {**title_mt.status(), "healthy": healthy},
        "queue": title_refiner.status(),
        "cache": title_cache.counts(),
    }


@router.get("/health/deep")
async def deep_health_check(_: bool = Depends(verify_admin_access)):
    """Kiểm tra sức khỏe sâu của hệ thống máy chủ MiniPC (SQLite, Disk, Antigravity CLI). Chỉ quản trị."""
    import os
    import time
    import shutil
    from services.agy_runner import AgyRunnerMixin

    checks = {}
    is_healthy = True

    # 1. SQLite Read & Write check
    try:
        ping_key = _DEEP_PING_KEY
        db.table("_health_check").upsert({"id": ping_key, "timestamp": time.time()}).execute()
        read_back = db.table("_health_check").select("*").eq("id", ping_key).single().execute()
        if read_back.data and read_back.data.get("id") == ping_key:
            checks["sqlite"] = {"status": "ok"}
        else:
            checks["sqlite"] = {"status": "degraded", "detail": "Read verification failed"}
            is_healthy = False
    except Exception as e:
        checks["sqlite"] = {"status": "error", "error": str(e)}
        is_healthy = False

    # 2. Disk Space check (MiniPC storage health — ưu tiên kiểm tra phân vùng chứa thư mục dữ liệu data/)
    try:
        base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        data_dir = os.path.join(base_dir, "data")
        target_dir = data_dir if os.path.isdir(data_dir) else base_dir
        usage = shutil.disk_usage(target_dir)
        free_gb = round(usage.free / (1024 ** 3), 2)
        total_gb = round(usage.total / (1024 ** 3), 2)
        used_percent = round((usage.used / usage.total) * 100, 1)
        checks["disk"] = {
            "status": "warning" if free_gb < 2.0 else "ok",
            "free_gb": free_gb,
            "total_gb": total_gb,
            "used_percent": used_percent
        }
        if free_gb < 0.5:
            is_healthy = False
    except Exception as e:
        checks["disk"] = {"status": "error", "error": str(e)}

    # 3. Antigravity AI CLI check
    runner = AgyRunnerMixin()
    agy_path = runner._get_agy_path()
    checks["antigravity_cli"] = {
        "status": "ready" if agy_path else "not_found",
        "path": agy_path
    }

    return {
        "status": "healthy" if is_healthy else "degraded",
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "checks": checks
    }

