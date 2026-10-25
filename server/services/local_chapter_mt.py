"""Bộ Dịch Chương Truyện Offline Ban Đêm bằng mô hình cục bộ `Qwen3-4B-Q4_K_M.gguf` (`truyencloud-qwen`).

Chỉ dùng cặp 2 mô hình theo đúng cấu hình:
1. Mặc định / Chính: `gemini-3.8-flash-medium` (Gemini 3.8 Flash Medium).
2. Dịch ngầm ban đêm / Dự phòng Offline: `Qwen3-4B-Q4_K_M.gguf` chạy qua `llama.cpp` (`http://truyencloud-qwen:8081`).
"""

import asyncio
import logging
import os
import re
from typing import Awaitable, Callable, List, Optional, Sequence, Tuple

import httpx

from models.translate import TermItem
from services.hanviet_dict import has_cjk, transliterate_to_hanviet
from services.title_dict import title_dict
from services.title_mt_validate import strip_think
from services.translation_validation import has_excess_cjk, validate_ai_translation

logger = logging.getLogger("truyencloud.local_chapter_mt")

ENGINE_QWEN_OFFLINE = "QWEN3_4B_OFFLINE"
MAX_CHUNK_CHARS = 260
QWEN_SYSTEM_PROMPT = (
    "/no_think\n"
    "Bạn là dịch giả tiểu thuyết tiếng Trung sang tiếng Việt chuyên nghiệp. "
    "Hãy dịch đoạn văn sau sang tiếng Việt tự nhiên, mượt mà, đúng ngữ cảnh và giữ nguyên xưng hô, tên riêng theo bảng thuật ngữ. "
    "Chỉ xuất ra bản dịch tiếng Việt, tuyệt đối không giải thích thêm."
)


def qwen_base_url() -> str:
    raw = (os.getenv("QWEN_MT_URL") or "http://truyencloud-qwen:8081").rstrip("/")
    for suffix in ("/v1/chat/completions", "/chat/completions"):
        if raw.endswith(suffix):
            raw = raw[: -len(suffix)].rstrip("/")
    return raw


async def qwen_healthy() -> bool:
    url = qwen_base_url() + "/health"
    try:
        async with httpx.AsyncClient(timeout=2.5) as client:
            resp = await client.get(url)
            return resp.status_code == 200
    except Exception:
        return False


def _extract_chunk_hints(
    chunk: str, glossary: Optional[Sequence[TermItem]] = None, max_hints: int = 25
) -> List[Tuple[str, str]]:
    hints: List[Tuple[str, str]] = []
    seen = set()
    for g in glossary or ():
        src = (g.source or "").strip()
        tgt = (g.target or "").strip()
        if src and tgt and src in chunk and src not in seen:
            seen.add(src)
            hints.append((src, tgt))
            if len(hints) >= max_hints:
                return hints
    d = title_dict.get()
    if d is not None:
        try:
            for zh, vi in d.hints_for(chunk[:160], cap=max_hints - len(hints)):
                if zh not in seen:
                    seen.add(zh)
                    hints.append((zh, vi))
        except Exception:
            pass
    return hints[:max_hints]


def _split_into_chunks(content: str, max_chars: int = MAX_CHUNK_CHARS) -> List[str]:
    paras = [p.strip() for p in content.splitlines() if p.strip()]
    chunks: List[str] = []
    current: List[str] = []
    current_len = 0
    for p in paras:
        if len(p) > max_chars:
            if current:
                chunks.append("\n".join(current))
                current, current_len = [], 0
            sents = re.split(r"(?<=[。！？!?])\s*", p)
            buf = ""
            for s in sents:
                if not s:
                    continue
                if len(buf) + len(s) > max_chars and buf:
                    chunks.append(buf)
                    buf = s
                else:
                    buf += s
            if buf:
                chunks.append(buf)
            continue
        if current_len + len(p) + 1 > max_chars and current:
            chunks.append("\n".join(current))
            current = [p]
            current_len = len(p)
        else:
            current.append(p)
            current_len += len(p) + 1
    if current:
        chunks.append("\n".join(current))
    return chunks


def _extract_prev_tail(translated_chunk: str, max_chars: int = 140) -> str:
    text = (translated_chunk or "").strip()
    if not text:
        return ""
    sents = [s.strip() for s in re.split(r"(?<=[.!?…\n])\s+", text) if s.strip()]
    tail = sents[-1] if sents else text
    return tail[-max_chars:].strip()


async def _call_qwen_chunk(
    chunk: str,
    hints: Sequence[Tuple[str, str]],
    timeout: float = 240.0,
    prev_tail: str = "",
) -> Optional[str]:
    glossary_block = ""
    if hints:
        rows = "\n".join(f"- {zh} = {vi}" for zh, vi in hints)
        glossary_block = f"Bảng thuật ngữ / Tên riêng:\n{rows}\n\n"
    context_block = ""
    if prev_tail and prev_tail.strip():
        context_block = (
            f"Ngữ cảnh câu cuối đoạn trước (chỉ tham khảo xưng hô, không dịch lại): "
            f"{prev_tail.strip()[-140:]}\n\n"
        )
    user_msg = f"{glossary_block}{context_block}Dịch đoạn văn tiếng Trung sau sang tiếng Việt:\n\n{chunk}\n/no_think"
    body = {
        "messages": [
            {"role": "system", "content": QWEN_SYSTEM_PROMPT},
            {"role": "user", "content": user_msg},
        ],
        "temperature": 0.2,
        "top_p": 0.9,
        "repeat_penalty": 1.08,
        "max_tokens": min(1200, max(240, int(len(chunk) * 2.4))),
        "cache_prompt": True,
        "chat_template_kwargs": {"enable_thinking": False},
    }
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(qwen_base_url() + "/v1/chat/completions", json=body)
            if resp.status_code != 200:
                return None
            data = resp.json()
            msg = data["choices"][0]["message"]
            raw = msg.get("content") or ""
            cleaned = strip_think(raw).strip()
            if cleaned and has_cjk(cleaned) and not has_excess_cjk(cleaned):
                cleaned = re.sub(r"[㐀-䶿一-鿿豈-﫿]+", lambda m: transliterate_to_hanviet(m.group(0)), cleaned)
            return cleaned or None
    except Exception as exc:
        logger.debug(f"Qwen3-4B chunk lỗi: {exc}")
        return None


async def translate_chapter_offline(
    content: str,
    glossary: Optional[Sequence[TermItem]] = None,
) -> Optional[str]:
    """Dịch nguyên chương bằng mô hình cục bộ `Qwen3-4B-Q4_K_M.gguf` trên MiniPC."""
    if not content or not content.strip():
        return ""
    if not await qwen_healthy():
        return None
    chunks = _split_into_chunks(content)
    if not chunks:
        return ""

    translated_chunks: List[str] = []
    prev_tail = ""
    for chunk in chunks:
        hints = _extract_chunk_hints(chunk, glossary)
        cleaned = await _call_qwen_chunk(chunk, hints, timeout=240.0, prev_tail=prev_tail)
        if not cleaned or has_excess_cjk(cleaned):
            return None
        translated_chunks.append(cleaned)
        prev_tail = _extract_prev_tail(cleaned)
        await asyncio.sleep(0.2)

    result = "\n\n".join(c for c in translated_chunks if c.strip())
    return result.strip() or None


def _split_paragraph_pair(
    source: str, processed: str
) -> Optional[Tuple[Tuple[str, str], Tuple[str, str]]]:
    src_paras = [p.strip() for p in source.splitlines() if p.strip()]
    proc_paras = [p.strip() for p in processed.splitlines() if p.strip()]
    if len(src_paras) < 2 or len(proc_paras) < 2:
        return None
    mid_s = len(src_paras) // 2
    mid_p = mid_s if len(src_paras) == len(proc_paras) else len(proc_paras) // 2
    return (
        ("\n".join(src_paras[:mid_s]), "\n".join(proc_paras[:mid_p])),
        ("\n".join(src_paras[mid_s:]), "\n".join(proc_paras[mid_p:])),
    )


async def translate_with_refusal_split(
    source: str,
    processed: str,
    ai_translate_fn: Callable[[str], Awaitable[Optional[str]]],
    target_language: str = "vi",
    glossary: Optional[Sequence[TermItem]] = None,
    allow_offline_leaf: bool = False,
    max_depth: int = 4,
    min_split_chars: int = 120,
) -> Optional[str]:
    """Khi nguyên chương bị bộ lọc an toàn từ chối, tự động chia đôi đệ quy theo đoạn văn để AI dịch từng phần."""
    root_halves = _split_paragraph_pair(source, processed)
    if not root_halves:
        return None
    literary_prefix = (
        "[Lưu ý: Đây là trích đoạn tiểu thuyết văn học / y khoa hư cấu, "
        "chỉ dịch thuật ngôn ngữ sang tiếng Việt, không tạo nội dung độc hại]\n\n"
    )

    async def _solve(src_part: str, proc_part: str, depth: int) -> Optional[str]:
        res: Optional[str] = None
        try:
            res = await ai_translate_fn(f"{literary_prefix}{proc_part}")
        except Exception as exc:
            logger.debug(f"AI dịch đoạn chia nhỏ (depth={depth}) lỗi: {exc}")
        reason = validate_ai_translation(src_part, res, target_language) if res else "empty"
        if reason is None and res:
            return res.strip()
        if reason in ("refusal", "empty", "truncated") and depth < max_depth and len(src_part) >= min_split_chars:
            sub_halves = _split_paragraph_pair(src_part, proc_part)
            if sub_halves:
                (s1, p1), (s2, p2) = sub_halves
                logger.info(
                    f"Đoạn ({len(src_part)} ký tự) gặp {reason} ở mức chia {depth}/{max_depth} — "
                    f"tiếp tục tách đôi ({len(s1)} + {len(s2)} ký tự)..."
                )
                t1 = await _solve(s1, p1, depth + 1)
                if not t1:
                    return None
                t2 = await _solve(s2, p2, depth + 1)
                if not t2:
                    return None
                return f"{t1}\n\n{t2}"
        if reason == "refusal" and allow_offline_leaf and target_language == "vi" and len(proc_part) <= 800:
            logger.info(
                f"Đoạn nhỏ ({len(proc_part)} ký tự) vẫn bị từ chối ở mức chia {depth}, "
                f"chuyển riêng đoạn này sang Qwen3-4B Offline..."
            )
            leaf_res = await translate_chapter_offline(proc_part, glossary)
            if leaf_res and not has_excess_cjk(leaf_res):
                return leaf_res.strip()
        return None

    (s1, p1), (s2, p2) = root_halves
    logger.info(
        f"Chương ({len(source)} ký tự) bị từ chối nguyên chương — "
        f"tách đôi theo đoạn văn ({len(s1)} + {len(s2)} ký tự) để dịch qua AI..."
    )
    t1 = await _solve(s1, p1, 1)
    if not t1:
        return None
    t2 = await _solve(s2, p2, 1)
    if not t2:
        return None
    combined = f"{t1}\n\n{t2}"
    if validate_ai_translation(source, combined, target_language) is None:
        logger.info(f"Dịch tách đoạn thành công qua AI ({len(combined)} ký tự, giảm tải CPU)!")
        return combined
    return None

