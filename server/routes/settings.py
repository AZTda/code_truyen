import json
from pathlib import Path
from typing import Optional, List, Dict, Any
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from config import settings
from services.translation_worker import translation_worker
from services.model_catalog import model_catalog_service
from services.auth_token import current_user
from routes.admin import verify_admin_access

router = APIRouter(prefix="/settings", tags=["Settings"])

DATA_DIR = Path(__file__).parent.parent / "data"
PROFILE_FILE = DATA_DIR / "translation_profile.json"

DEFAULT_PROFILE = {
    "genre": "Tiên hiệp / Huyền huyễn",
    "sino_vietnamese_ratio": "Cân bằng tự nhiên",
    "custom_prompt": "Dịch giọng văn tiểu thuyết trôi chảy, xưng hô phù hợp bối cảnh, giữ nguyên kiếm chiêu và danh xưng tôn kính.",
    "prefetch_chapters": 1,
    "auto_recrawl": True,
    "reader_mode": "translated",  # translated, original, bilingual
}


def load_profile() -> Dict[str, Any]:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    if not PROFILE_FILE.exists():
        with open(PROFILE_FILE, "w", encoding="utf-8") as f:
            json.dump(DEFAULT_PROFILE, f, ensure_ascii=False, indent=2)
        return DEFAULT_PROFILE.copy()
    try:
        with open(PROFILE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
            merged = DEFAULT_PROFILE.copy()
            merged.update(data)
            return merged
    except Exception:
        return DEFAULT_PROFILE.copy()


def save_profile(data: Dict[str, Any]):
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with open(PROFILE_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


class ApiKeyRequest(BaseModel):
    api_key: str


class SettingsResponse(BaseModel):
    has_api_key: bool = False
    engine: str = "ANTIGRAVITY_CLI"
    host: str
    port: int
    agy_installed: bool = False
    agy_authenticated: bool = False
    active_model: str = "gemini-3.8-flash-high"
    cli_version: str = "1.1.27"


class ModelItem(BaseModel):
    id: str
    name: str
    provider: str
    reasoning: str


class ModelCatalogResponse(BaseModel):
    cli_version: str
    last_updated: str
    active_model: str
    active_effort: str
    timeout_seconds: int
    models: List[ModelItem]


class SelectModelRequest(BaseModel):
    model_id: str
    effort: Optional[str] = None
    timeout_seconds: Optional[int] = None


class TranslationProfileRequest(BaseModel):
    genre: Optional[str] = None
    sino_vietnamese_ratio: Optional[str] = None
    custom_prompt: Optional[str] = None
    prefetch_chapters: Optional[int] = None
    auto_recrawl: Optional[bool] = None
    reader_mode: Optional[str] = None


@router.get("", response_model=SettingsResponse)
async def get_settings():
    """Lấy trạng thái cấu hình backend và động cơ dịch độc quyền Antigravity CLI."""
    agy_path = translation_worker._get_agy_path()
    agy_ready = translation_worker.is_agy_ready()
    catalog = model_catalog_service.get_catalog(auto_check=True)
    return SettingsResponse(
        has_api_key=False,
        engine="ANTIGRAVITY_CLI",
        host=settings.HOST,
        port=settings.PORT,
        agy_installed=bool(agy_path),
        agy_authenticated=agy_ready,
        active_model=catalog.get("active_model", "gemini-3.8-flash-high"),
        cli_version=catalog.get("cli_version", "1.1.27"),
    )


@router.get("/models", response_model=ModelCatalogResponse)
async def get_models():
    """Lấy danh mục model dịch thuật động và model đang kích hoạt."""
    catalog = model_catalog_service.get_catalog(auto_check=True)
    return ModelCatalogResponse(
        cli_version=catalog.get("cli_version", "1.1.27"),
        last_updated=catalog.get("last_updated", ""),
        active_model=catalog.get("active_model", "gemini-3.8-flash-high"),
        active_effort=catalog.get("active_effort", "high"),
        timeout_seconds=catalog.get("timeout_seconds", 900),
        models=[ModelItem(**m) for m in catalog.get("models", [])],
    )


@router.post("/models/refresh", response_model=ModelCatalogResponse)
async def refresh_models(_: bool = Depends(verify_admin_access)):
    """Quét lại trực tiếp từ Antigravity CLI 'agy models' để cập nhật các model mới nhất. Chỉ quản trị."""
    catalog = model_catalog_service.refresh_catalog()
    return ModelCatalogResponse(
        cli_version=catalog.get("cli_version", "1.1.27"),
        last_updated=catalog.get("last_updated", ""),
        active_model=catalog.get("active_model", "gemini-3.8-flash-high"),
        active_effort=catalog.get("active_effort", "high"),
        timeout_seconds=catalog.get("timeout_seconds", 900),
        models=[ModelItem(**m) for m in catalog.get("models", [])],
    )


@router.post("/models/select", response_model=ModelCatalogResponse)
async def select_model(req: SelectModelRequest, _: bool = Depends(verify_admin_access)):
    """Chọn model dịch thuật chính, mức độ suy luận và timeout — áp dụng cho cả máy chủ nên chỉ quản trị."""
    try:
        catalog = model_catalog_service.select_model(
            model_id=req.model_id,
            effort=req.effort,
            timeout_seconds=req.timeout_seconds
        )
        return ModelCatalogResponse(
            cli_version=catalog.get("cli_version", "1.1.27"),
            last_updated=catalog.get("last_updated", ""),
            active_model=catalog.get("active_model", "gemini-3.8-flash-high"),
            active_effort=catalog.get("active_effort", "high"),
            timeout_seconds=catalog.get("timeout_seconds", 900),
            models=[ModelItem(**m) for m in catalog.get("models", [])],
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/profile")
async def get_translation_profile():
    """Lấy hồ sơ văn phong và cấu hình dịch thuật."""
    return load_profile()


@router.post("/profile")
async def update_translation_profile(req: TranslationProfileRequest, _: bool = Depends(verify_admin_access)):
    """Cập nhật hồ sơ văn phong dùng chung cho mọi người dùng — chỉ quản trị."""
    current = load_profile()
    if req.genre is not None:
        current["genre"] = req.genre
    if req.sino_vietnamese_ratio is not None:
        current["sino_vietnamese_ratio"] = req.sino_vietnamese_ratio
    if req.custom_prompt is not None:
        current["custom_prompt"] = req.custom_prompt
    if req.prefetch_chapters is not None:
        current["prefetch_chapters"] = req.prefetch_chapters
    if req.auto_recrawl is not None:
        current["auto_recrawl"] = req.auto_recrawl
    if req.reader_mode is not None:
        current["reader_mode"] = req.reader_mode
    save_profile(current)
    return {"status": "success", "profile": current}


@router.post("/api-key")
async def update_api_key(req: ApiKeyRequest, auth: dict = Depends(current_user)):
    """Stub tương thích ngược: Hệ thống hoạt động độc quyền qua Antigravity CLI ($0 API)."""
    return {
        "status": "success",
        "message": "Hệ thống đang hoạt động độc quyền trên Antigravity CLI kết nối Tài khoản Google ($0 API).",
        "engine": "ANTIGRAVITY_CLI"
    }
