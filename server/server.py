import os
import math
import logging
from contextlib import asynccontextmanager
import uvicorn
from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from config import settings
from database import db, checkpoint, close_all_connections
from services.storage_service import storage_service
from services.auto_updater import auto_updater
from services.body_limit import BodySizeLimitMiddleware
from services.cache_janitor import cache_janitor
from services.rate_limit import rate_limit_middleware
from routes import (
    health,
    novels,
    chapters,
    translate,
    scrape,
    images,
    auth,
    settings as settings_route,
    import_route,
    lore,
    english,
    english_immersion,
    tts,
    progress,
    bookmarks,
    vocabulary_sync,
    companion,
    jobs,
    admin,
    admin_novels,
    admin_page,
    app_update
)

logger = logging.getLogger("truyencloud.server")

async def _flush_database(close_connections: bool = False) -> None:
    """Gộp -wal của SQLite vào tệp chính (và đóng kết nối). docker-compose chỉ mount MỘT tệp truyencloud.db nên
    dữ liệu còn nằm trong -wal sẽ mất khi container bị tạo lại."""
    import asyncio
    try:
        await asyncio.to_thread(checkpoint)
        if close_connections:
            close_all_connections()
    except Exception as e:
        logger.warning(f"Lỗi checkpoint SQLite lúc tắt: {e}")


@asynccontextmanager
async def lifespan(app: FastAPI):
    import asyncio
    admin.warn_if_admin_key_missing()

    # Startup: Nạp truyện và chương từ đĩa vào database cache chạy nền, không chặn server khởi động
    try:
        import asyncio
        asyncio.create_task(asyncio.to_thread(storage_service.sync_all_to_database, db))
    except Exception as e:
        logger.warning(f"Lỗi khi đồng bộ dữ liệu đĩa vào database: {e}")

    # Khởi động tác vụ quét và dịch trước chương mới chạy ngầm
    try:
        auto_updater.start()
        from services.overnight_translator import overnight_translator
        overnight_translator.start()
    except Exception as e:
        logger.warning(f"Lỗi khởi động auto_updater / overnight_translator: {e}")

    # Dọn các thư mục đệm theo TTL + LRU để không phình vô hạn trên MiniPC
    try:
        cache_janitor.start()
    except Exception as e:
        logger.warning(f"Lỗi khởi động cache_janitor: {e}")

    # Khởi động sẵn warm Antigravity worker để lời gọi AI đầu tiên không cold-start
    try:
        import asyncio
        from services.agy_pool import agy_pool
        asyncio.create_task(agy_pool.warmup())
    except Exception as e:
        logger.warning(f"Lỗi warmup agy_pool: {e}")

    # Giọng đọc VieNeu chạy trên máy chủ: nạp mô hình ở thread nền (không chặn /v1/health) và dọn bộ đệm TTS khi khởi động
    try:
        from services import tts_cache
        from services.tts_local import local_tts
        local_tts.start_warmup()
        asyncio.create_task(asyncio.to_thread(tts_cache.sweeper.maybe_run, tts_cache.CACHE_DIR, True))
    except Exception as e:
        logger.warning(f"Lỗi khởi động giọng đọc local: {e}")

    # Tựa đề/tựa chương/từ khoá không còn qua Google: từ điển convert (tải + dựng lần đầu ở luồng nền) và hàng đợi
    # Hy-MT2 tinh chỉnh nền (chỉ chạy khi dịch vụ truyencloud-mt trả lời /health).
    try:
        from services.title_dict import title_dict
        from services.title_refiner import title_refiner
        title_dict.prepare_async()
        title_refiner.start()
    except Exception as e:
        logger.warning(f"Lỗi khởi động từ điển/tinh chỉnh tựa đề: {e}")

    yield

    # Shutdown. Checkpoint NGAY (docker chỉ chờ ~10 giây rồi SIGKILL; các bước dừng dịch vụ bên dưới có thể chậm),
    # rồi checkpoint + đóng kết nối lần nữa ở cuối cho những gì được ghi trong lúc dừng.
    await _flush_database()
    try:
        auto_updater.stop()
    except Exception as e:
        logger.warning(f"Lỗi dừng auto_updater: {e}")

    try:
        cache_janitor.stop()
    except Exception as e:
        logger.warning(f"Lỗi dừng cache_janitor: {e}")

    try:
        from services.tts_prerender import render_queue
        render_queue.stop(join_seconds=0.5)
    except Exception as e:
        logger.warning(f"Lỗi dừng hàng đợi giọng đọc: {e}")

    try:
        from services.title_refiner import title_refiner
        from services.title_mt_client import title_mt
        from services.title_cache_store import title_cache
        await title_refiner.stop()
        await title_mt.aclose()
        title_cache.close()
    except Exception as e:
        logger.warning(f"Lỗi dừng hàng đợi tinh chỉnh tựa đề: {e}")

    try:
        from services.agy_pool import agy_pool
        await agy_pool.stop()
    except Exception as e:
        logger.warning(f"Lỗi dừng agy_pool: {e}")

    try:
        from services.http_shared import close_shared_client
        await close_shared_client()
        from services.fast_translator import fast_translator
        await fast_translator.aclose()
    except Exception as e:
        logger.warning(f"Lỗi đóng HTTP client dùng chung: {e}")

    await _flush_database(close_connections=True)


app = FastAPI(
    title="TruyenCloud API",
    description="Backend API Gateway for TruyenCloud 2026 — Thin Client + Fat Backend architecture",
    version="1.0.0",
    lifespan=lifespan,
)

def _finite_only(value, depth=0):
    """Đổi NaN/Infinity thành chuỗi để thông báo lỗi 422 còn tuần tự hoá được thành JSON chuẩn."""
    if isinstance(value, float) and not math.isfinite(value):
        return repr(value)
    if depth > 20:
        return repr(value)
    if isinstance(value, dict):
        return {key: _finite_only(item, depth + 1) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_finite_only(item, depth + 1) for item in value]
    return value


@app.exception_handler(RequestValidationError)
async def validation_error_handler(request: Request, exc: RequestValidationError):
    # Mặc định FastAPI trả lại cả giá trị đầu vào trong lỗi; body JSON chứa NaN/Infinity (Python chấp nhận
    # còn JSON chuẩn thì không) làm chính phản hồi 422 không tuần tự hoá được và biến thành HTTP 500.
    return JSONResponse(status_code=422, content={"detail": jsonable_encoder(_finite_only(exc.errors()))})


# Chặn body quá lớn ngay ở tầng ASGI: một POST 100 MB không xác thực từng làm máy chủ cấp phát hàng trăm
# MB (FastAPI gom cả body vào RAM) trước khi trả 413. Phải đăng ký TRƯỚC rate_limit_middleware (middleware
# thêm sau nằm ngoài): nằm sát router thì ngoại lệ 413 khi cắt luồng chunked tới thẳng FastAPI. Bọc ngoài một
# BaseHTTPMiddleware nó bị gói thành ExceptionGroup và bị đổi thành "400 There was an error parsing the body".
app.add_middleware(BodySizeLimitMiddleware)

# Giới hạn tần suất: mỗi lời gọi AI là một tiến trình Antigravity 25-90 giây,
# không giới hạn thì một vòng lặp đơn giản cũng đủ làm treo máy chủ.
app.middleware("http")(rate_limit_middleware)

# CORS middleware — configurable via CORS_ORIGINS env var.
# App di động không cần CORS, nên production không bao giờ mở "*".
_cors_origins = settings.effective_cors_origins
if not settings.DEBUG and "*" in settings.cors_origins_list:
    logger.warning(
        "CORS_ORIGINS='*' bị BỎ QUA vì DEBUG=false: chỉ cho các origin liệt kê cụ thể (%s). "
        "Web client cần CORS thì đặt CORS_ORIGINS=https://ten-mien-cua-ban.",
        ", ".join(_cors_origins) or "không origin trình duyệt nào",
    )
elif not _cors_origins:
    logger.info("CORS: không cho phép origin trình duyệt nào (ứng dụng di động không cần CORS).")
elif settings.DEBUG and "*" in _cors_origins:
    logger.info("DEBUG mode: CORS cho phép tất cả origins.")

app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_origins,
    # Wildcard origins cannot safely be combined with credentialed browser
    # requests. The mobile client authenticates with Bearer headers, so it does
    # not need cookie credentials when development CORS is intentionally open.
    allow_credentials="*" not in _cors_origins,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Mount static files directory
static_dir = os.path.join(os.path.dirname(__file__), "static")
index_html_path = os.path.join(static_dir, "index.html")

if os.path.exists(static_dir):
    app.mount("/static", StaticFiles(directory=static_dir), name="static")

@app.api_route("/", methods=["GET", "HEAD"], response_class=HTMLResponse)
async def serve_home():
    if os.path.exists(index_html_path):
        return FileResponse(index_html_path)
    return HTMLResponse("<h1>TruyenCloud API Server</h1><p>Vui lòng vào <a href='/v1/app/download-latest'>/v1/app/download-latest</a> để tải APK.</p>")

@app.api_route("/download", methods=["GET", "HEAD"], response_class=HTMLResponse)
async def serve_download():
    if os.path.exists(index_html_path):
        return FileResponse(index_html_path)
    return FileResponse(os.path.join(static_dir, "truyencloud-latest.apk"))

# Register v1 routes
app.include_router(health.router, prefix="/v1")
app.include_router(auth.router, prefix="/v1")
app.include_router(novels.router, prefix="/v1")
app.include_router(chapters.router, prefix="/v1")
app.include_router(translate.router, prefix="/v1")
app.include_router(scrape.router, prefix="/v1")
app.include_router(images.router, prefix="/v1")
app.include_router(settings_route.router, prefix="/v1")
app.include_router(import_route.router, prefix="/v1")
app.include_router(lore.router, prefix="/v1")
app.include_router(english.router, prefix="/v1")
app.include_router(english_immersion.router, prefix="/v1")
app.include_router(tts.router, prefix="/v1")
app.include_router(progress.router, prefix="/v1")
app.include_router(bookmarks.router, prefix="/v1")
app.include_router(vocabulary_sync.router, prefix="/v1")
app.include_router(companion.router, prefix="/v1")
app.include_router(jobs.router, prefix="/v1")
app.include_router(app_update.router, prefix="/v1")
app.include_router(admin.router)
app.include_router(admin_novels.router)
app.include_router(admin_page.router)

if __name__ == "__main__":
    uvicorn.run(
        "server:app",
        host=settings.HOST,
        port=settings.PORT,
        reload=settings.DEBUG,
    )
