"""Async client for the Hy-MT2 llama.cpp server (compose service truyencloud-mt, internal network only).

Never raises to callers: any network error, timeout, HTTP error or invalid output returns None. Interactive
vi->zh search calls use a short timeout (TITLE_MT_INTERACTIVE_TIMEOUT, 1.5 s); background refinement uses
TITLE_MT_BACKGROUND_TIMEOUT (30 s) per request.
"""

import logging
import os
import time
from typing import List, Optional, Sequence, Tuple

import httpx

from services.title_mt_prompts import CHAT_PATH, VI2ZH, ZH2VI, batch_body, single_body
from services.title_mt_validate import clean_vi, clean_zh_query, leaks, strip_think, validate_batch
from services.title_pinyin import VnCheck

logger = logging.getLogger("truyencloud.title_mt")

Hints = Sequence[Tuple[str, str]]


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, "") or default)
    except ValueError:
        return default


class TitleMTClient:
    HEALTH_TTL = 15.0

    def __init__(self):
        self._client: Optional[httpx.AsyncClient] = None
        self._health: Tuple[float, bool] = (0.0, False)
        self.stats = {"requests": 0, "errors": 0, "rejected": 0}

    @property
    def enabled(self) -> bool:
        return (os.getenv("TITLE_MT_ENABLED", "1").strip() or "1") != "0"

    @property
    def base_url(self) -> str:
        return (os.getenv("TITLE_MT_URL") or "http://truyencloud-mt:8080").rstrip("/")

    @property
    def interactive_timeout(self) -> float:
        return _env_float("TITLE_MT_INTERACTIVE_TIMEOUT", 1.5)

    @property
    def background_timeout(self) -> float:
        return _env_float("TITLE_MT_BACKGROUND_TIMEOUT", 30.0)

    def _http(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(limits=httpx.Limits(max_connections=4, max_keepalive_connections=2))
        return self._client

    async def aclose(self) -> None:
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
        self._client = None

    def mark_down(self) -> None:
        self._health = (time.monotonic(), False)

    async def healthy(self, max_age: Optional[float] = None) -> bool:
        """GET /health (llama.cpp trả 503 khi đang nạp mô hình). Kết quả được nhớ HEALTH_TTL giây."""
        if not self.enabled:
            return False
        checked, ok = self._health
        if time.monotonic() - checked < (self.HEALTH_TTL if max_age is None else max_age):
            return ok
        try:
            resp = await self._http().get(self.base_url + "/health", timeout=2.0)
            ok = resp.status_code == 200
        except Exception:
            ok = False
        self._health = (time.monotonic(), ok)
        return ok

    async def _chat(self, body: dict, timeout: float) -> Optional[str]:
        if not self.enabled:
            return None
        self.stats["requests"] += 1
        try:
            resp = await self._http().post(self.base_url + CHAT_PATH, json=body, timeout=timeout)
            if resp.status_code != 200:
                self.stats["errors"] += 1
                if resp.status_code >= 500:
                    self.mark_down()
                return None
            choice = resp.json()["choices"][0]
            if choice.get("finish_reason") == "length":
                # Hết max_tokens giữa chừng: bản dịch bị cụt nhưng vẫn qua được các kiểm tra khác — bỏ. Trả "" (không
                # phải None = lỗi mạng) để lô đánh số vẫn dịch lại từng mục riêng lẻ.
                self.stats["rejected"] += 1
                return ""
            return strip_think(choice["message"].get("content") or "")
        except httpx.TimeoutException:
            self.stats["errors"] += 1
            return None
        except Exception as e:
            self.stats["errors"] += 1
            self.mark_down()
            logger.debug("Hy-MT2 không phản hồi: %s", e)
            return None

    async def zh_to_vi(self, text: str, hints: Hints = (), timeout: Optional[float] = None,
                       vn_check: Optional[VnCheck] = None) -> Optional[str]:
        raw = await self._chat(single_body(text, ZH2VI, hints), timeout or self.background_timeout)
        vi = clean_vi(raw, text, vn_check) if raw else None
        if raw and (vi is None or leaks(vi, text, hints)):
            self.stats["rejected"] += 1
            return None
        return vi

    async def zh_to_vi_batch(self, texts: Sequence[str], hints: Hints = (),
                             item_hints: Optional[Sequence[Hints]] = None,
                             timeout: Optional[float] = None,
                             vn_check: Optional[VnCheck] = None) -> List[Optional[str]]:
        """Một request đánh số cho cả lô; mục nào không qua kiểm tra được dịch lại riêng với gợi ý của chính nó."""
        texts = list(texts)
        if not texts:
            return []
        if len(texts) == 1:
            own = item_hints[0] if item_hints else hints
            return [await self.zh_to_vi(texts[0], own, timeout, vn_check)]
        timeout = timeout or self.background_timeout
        raw = await self._chat(batch_body(texts, ZH2VI, hints), timeout)
        if raw is None:
            return [None] * len(texts)
        out = validate_batch(raw, texts, hints, vn_check)
        for i, vi in enumerate(out):
            if vi is None:
                self.stats["rejected"] += 1
                own = item_hints[i] if item_hints else ()
                out[i] = await self.zh_to_vi(texts[i], own, timeout, vn_check)
        return out

    async def vi_to_zh(self, query: str, timeout: Optional[float] = None,
                       hints: Hints = ()) -> Optional[str]:
        if not hints:
            try:
                from services.query_vp_reverse import extract_query_segments_and_hints
                from services.title_dict import title_dict
                _, auto_hints = extract_query_segments_and_hints(query, title_dict.get())
                hints = auto_hints
            except Exception:
                hints = ()
        raw = await self._chat(single_body(query, VI2ZH, hints=hints, max_tokens=48), timeout or self.interactive_timeout)
        zh = clean_zh_query(raw) if raw else None
        if raw and zh is None:
            self.stats["rejected"] += 1
        return zh

    def status(self) -> dict:
        checked, ok = self._health
        return {"enabled": self.enabled, "url": self.base_url, "healthy": ok,
                "checked_ago_s": round(time.monotonic() - checked, 1) if checked else None, **self.stats}


title_mt = TitleMTClient()
