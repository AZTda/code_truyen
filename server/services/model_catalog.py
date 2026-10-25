"""Service managing dynamic Antigravity translation models and auto-updates."""

import os
import shutil
import subprocess
import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Dict, Any, List, Optional
from services.agy_effort import effort_for_model

logger = logging.getLogger("truyencloud.model_catalog")

DATA_DIR = Path(__file__).parent.parent / "data"
CATALOG_FILE = DATA_DIR / "models_cache.json"

DEFAULT_MODELS = [
    {"id": "gemini-3.8-flash-low", "name": "Gemini 3.8 Flash (Low - Tốc độ cực đại)", "provider": "Google", "reasoning": "low"},
    {"id": "gemini-3.8-flash-medium", "name": "Gemini 3.8 Flash (Medium)", "provider": "Google", "reasoning": "medium"},
    {"id": "gemini-3.8-flash-high", "name": "Gemini 3.8 Flash (High)", "provider": "Google", "reasoning": "high"},
    {"id": "gemini-3.7-flash-low", "name": "Gemini 3.7 Flash (Low)", "provider": "Google", "reasoning": "low"},
    {"id": "gemini-3.7-flash-medium", "name": "Gemini 3.7 Flash (Medium)", "provider": "Google", "reasoning": "medium"},
    {"id": "gemini-3.7-flash-high", "name": "Gemini 3.7 Flash (High)", "provider": "Google", "reasoning": "high"},
    {"id": "gemini-3.1-pro-low", "name": "Gemini 3.1 Pro (Low)", "provider": "Google", "reasoning": "low"},
    {"id": "gemini-3.1-pro-high", "name": "Gemini 3.1 Pro (High)", "provider": "Google", "reasoning": "high"},
    {"id": "claude-sonnet-4-6", "name": "Claude Sonnet 4.6 (Thinking)", "provider": "Anthropic", "reasoning": "thinking"},
    {"id": "claude-opus-4-6-thinking", "name": "Claude Opus 4.6 (Thinking)", "provider": "Anthropic", "reasoning": "thinking"},
    {"id": "gpt-oss-120b-medium", "name": "GPT-OSS 120B (Medium)", "provider": "OpenAI", "reasoning": "medium"},
]


class ModelCatalogService:
    def __init__(self):
        self._agy_path: Optional[str] = None
        self._cache_data: Optional[Dict[str, Any]] = None
        self._cache_mtime: Optional[float] = None
        self._ensure_cache_file()

    def _get_agy_path(self) -> Optional[str]:
        if self._agy_path and os.path.exists(self._agy_path):
            return self._agy_path

        candidates = [
            shutil.which("agy"),
            os.path.expanduser("~/.local/bin/agy"),
            "/usr/local/bin/agy",
        ]
        for path in candidates:
            if path and os.path.exists(path) and os.access(path, os.X_OK):
                self._agy_path = path
                return path
        return None

    def _get_cli_version(self) -> str:
        # Memo 10 phút: tránh subprocess 5s chặn event loop mỗi lần mở settings
        import time
        now = time.monotonic()
        cached = getattr(self, "_cli_version_cache", None)
        if cached and now - cached[1] < 600.0:
            return cached[0]
        agy = self._get_agy_path()
        if not agy:
            return "unknown"
        version = "1.1.27"
        try:
            res = subprocess.run([agy, "--version"], capture_output=True, text=True, timeout=5)
            if res.returncode == 0 and res.stdout.strip():
                version = res.stdout.strip().splitlines()[0].strip()
        except Exception as e:
            logger.warning(f"Error fetching agy version: {e}")
        self._cli_version_cache = (version, now)
        return version

    def _ensure_cache_file(self):
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        if not CATALOG_FILE.exists():
            initial_data = {
                "cli_version": self._get_cli_version(),
                "last_updated": datetime.utcnow().isoformat() + "Z",
                "active_model": "gemini-3.8-flash-medium",
                "active_effort": "medium",
                "timeout_seconds": 120,
                "models": DEFAULT_MODELS,
            }
            self._save_cache(initial_data)

    def _load_cache(self) -> Dict[str, Any]:
        # Memo hóa theo mtime: get_active_model/effort/timeout được gọi nhiều lần
        # cho MỖI lượt dịch chương — không mở + parse lại JSON khi tệp không đổi.
        try:
            mtime = os.path.getmtime(CATALOG_FILE)
            if self._cache_data is not None and mtime == self._cache_mtime:
                return self._cache_data
            with open(CATALOG_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            self._cache_data, self._cache_mtime = data, mtime
            return data
        except Exception:
            return {
                "cli_version": "1.1.27",
                "last_updated": datetime.utcnow().isoformat() + "Z",
                "active_model": "gemini-3.8-flash-medium",
                "active_effort": "medium",
                "timeout_seconds": 120,
                "models": DEFAULT_MODELS,
            }

    def _save_cache(self, data: Dict[str, Any]):
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        with open(CATALOG_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        self._cache_data = data
        try:
            self._cache_mtime = os.path.getmtime(CATALOG_FILE)
        except Exception:
            self._cache_mtime = None

    def query_agy_models(self) -> List[Dict[str, str]]:
        """Invokes 'agy models' and parses the output into structured model objects."""
        agy = self._get_agy_path()
        if not agy:
            logger.warning("agy binary not found, returning default models")
            return DEFAULT_MODELS

        try:
            res = subprocess.run(
                [agy, "models"],
                capture_output=True,
                text=True,
                timeout=15
            )
            if res.returncode != 0 or not res.stdout:
                logger.warning(f"agy models returned code {res.returncode}: {res.stderr}")
                return DEFAULT_MODELS

            lines = res.stdout.strip().splitlines()
            parsed_models: List[Dict[str, str]] = []
            for line in lines:
                line = line.strip()
                if not line or line.lower().startswith("fetching"):
                    continue
                # Split by tab or multiple spaces
                parts = [p.strip() for p in line.split("\t") if p.strip()]
                if len(parts) == 1:
                    parts = [p.strip() for p in line.split("  ") if p.strip()]

                if parts:
                    model_id = parts[0]
                    name = parts[1] if len(parts) > 1 else model_id

                    provider = "Google"
                    if "claude" in model_id.lower():
                        provider = "Anthropic"
                    elif "gpt" in model_id.lower():
                        provider = "OpenAI"

                    reasoning = "default"
                    if "-high" in model_id.lower() or "high" in name.lower():
                        reasoning = "high"
                    elif "-medium" in model_id.lower() or "medium" in name.lower():
                        reasoning = "medium"
                    elif "-low" in model_id.lower() or "low" in name.lower():
                        reasoning = "low"
                    elif "thinking" in model_id.lower() or "thinking" in name.lower():
                        reasoning = "thinking"

                    parsed_models.append({
                        "id": model_id,
                        "name": name,
                        "provider": provider,
                        "reasoning": reasoning
                    })

            if parsed_models:
                return parsed_models
        except Exception as e:
            logger.error(f"Failed to query agy models: {e}")

        return DEFAULT_MODELS

    def get_catalog(self, auto_check: bool = True) -> Dict[str, Any]:
        """Returns catalog data, checking for CLI version update if auto_check is enabled."""
        cache = self._load_cache()
        if auto_check:
            current_ver = self._get_cli_version()
            cached_ver = cache.get("cli_version", "")
            if current_ver != cached_ver and current_ver != "unknown":
                logger.info(f"Antigravity CLI version changed from {cached_ver} to {current_ver}. Auto-updating models...")
                return self.refresh_catalog()
        return cache

    def refresh_catalog(self) -> Dict[str, Any]:
        """Forces refreshing models from Antigravity CLI and updating cache."""
        current_ver = self._get_cli_version()
        fresh_models = self.query_agy_models()

        cache = self._load_cache()
        cache["cli_version"] = current_ver
        cache["last_updated"] = datetime.utcnow().isoformat() + "Z"
        cache["models"] = fresh_models

        # Ensure active_model is still in the catalog, otherwise default to first flash-medium model
        model_ids = [m["id"] for m in fresh_models]
        if cache.get("active_model") not in model_ids:
            preferred = next((m["id"] for m in fresh_models if "flash-medium" in m["id"]), fresh_models[0]["id"])
            cache["active_model"] = preferred

        self._save_cache(cache)
        logger.info(f"Successfully refreshed {len(fresh_models)} models from Antigravity CLI (v{current_ver}).")
        return cache

    def select_model(
        self,
        model_id: str,
        effort: Optional[str] = None,
        timeout_seconds: Optional[int] = None
    ) -> Dict[str, Any]:
        """Selects the active translation model, reasoning effort, and timeout."""
        cache = self._load_cache()
        model_ids = [m["id"] for m in cache.get("models", [])]
        if model_id not in model_ids:
            # Refresh once to see if it's a newly added model
            cache = self.refresh_catalog()
            model_ids = [m["id"] for m in cache.get("models", [])]
            if model_id not in model_ids:
                raise ValueError(f"Model '{model_id}' không tồn tại trong danh mục Antigravity.")

        cache["active_model"] = model_id
        # Bậc suy luận nằm trong tên mô hình ("...-medium") luôn thắng: CLI từ chối --effort lệch với nó
        cache["active_effort"] = effort_for_model(model_id, effort or cache.get("active_effort"))
        if timeout_seconds and timeout_seconds > 0:
            cache["timeout_seconds"] = timeout_seconds

        self._save_cache(cache)
        return cache

    def get_active_model(self) -> str:
        cache = self._load_cache()
        return cache.get("active_model", "gemini-3.8-flash-medium")

    def get_active_effort(self) -> str:
        cache = self._load_cache()
        # Cài đặt cũ có thể lệch với mô hình đang chọn (mô hình ...-medium + effort "low"): trả bậc thật sự dùng được
        return effort_for_model(cache.get("active_model"), cache.get("active_effort", "medium"))

    def get_active_timeout(self) -> int:
        cache = self._load_cache()
        return cache.get("timeout_seconds", 900)


model_catalog_service = ModelCatalogService()
