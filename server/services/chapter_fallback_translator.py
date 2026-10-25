"""Dịch dự phòng NGUYÊN CHƯƠNG qua Google Translate khi AI thất bại.

`FastTranslator.translate_text` được viết cho TỰA ĐỀ: một request cho cả chương,
vứt kết quả nếu còn một chữ Hán rồi phiên âm Hán-Việt cả chương. Module này thay thế đường đó cho chương truyện:
chia theo đoạn thành khối <= 4500 ký tự, dịch song song có giới hạn, thử lại mỗi
khối một lần và giữ nguyên cấu trúc đoạn. Không bao giờ phiên âm Hán-Việt.
"""

import asyncio
import logging
import re
from typing import List, Optional

import httpx

from services.translation_validation import has_excess_cjk

logger = logging.getLogger("truyencloud.chapter_fallback")

GT_URL = "https://translate.googleapis.com/translate_a/single"
HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}

MAX_CHUNK_CHARS = 4500
CHUNK_CONCURRENCY = 3
REQUEST_TIMEOUT = 15.0

_SENTENCE_END_RE = re.compile(r"(?<=[。！？!?….])")


async def google_translate_raw(
    client: httpx.AsyncClient, text: str, sl: str = "auto", tl: str = "vi"
) -> Optional[str]:
    """Một lượt POST Google Translate; None nếu lỗi mạng/HTTP."""
    try:
        resp = await client.post(
            GT_URL,
            data={"client": "gtx", "sl": sl, "tl": tl, "dt": "t", "q": text},
            headers=HEADERS,
            timeout=REQUEST_TIMEOUT,
        )
        if resp.status_code != 200:
            return None
        segments = resp.json()[0] or []
        return "".join(seg[0] for seg in segments if seg and seg[0])
    except Exception as e:
        logger.debug(f"Google Translate chunk request failed: {e}")
        return None


def split_paragraphs(text: str) -> List[str]:
    return [ln.strip() for ln in (text or "").splitlines() if ln.strip()]


def _split_long_paragraph(para: str, limit: int) -> List[str]:
    pieces: List[str] = []
    buf = ""
    for sentence in _SENTENCE_END_RE.split(para):
        if not sentence:
            continue
        if buf and len(buf) + len(sentence) > limit:
            pieces.append(buf)
            buf = ""
        while len(sentence) > limit:
            pieces.append(sentence[:limit])
            sentence = sentence[limit:]
        buf += sentence
    if buf:
        pieces.append(buf)
    return pieces


def build_chunks(paragraphs: List[str], limit: int = MAX_CHUNK_CHARS) -> List[List[str]]:
    """Gom đoạn liên tiếp thành khối <= limit ký tự (tính cả dấu xuống dòng)."""
    chunks: List[List[str]] = []
    current: List[str] = []
    size = 0
    for para in paragraphs:
        parts = [para] if len(para) <= limit else _split_long_paragraph(para, limit)
        for part in parts:
            if current and size + len(part) + 1 > limit:
                chunks.append(current)
                current, size = [], 0
            current.append(part)
            size += len(part) + 1
    if current:
        chunks.append(current)
    return chunks


async def _translate_chunk(
    client: httpx.AsyncClient, sem: asyncio.Semaphore, chunk: List[str], tl: str
) -> Optional[List[str]]:
    payload = "\n".join(chunk)
    for attempt in range(2):
        async with sem:
            out = await google_translate_raw(client, payload, sl="auto", tl=tl)
        if out and out.strip() and not has_excess_cjk(out):
            lines = split_paragraphs(out)
            # Google giữ dấu xuống dòng; nếu lệch số dòng vẫn dùng được, chỉ mất căn đoạn.
            return lines if lines else None
        if attempt == 0:
            await asyncio.sleep(0.5)
    return None


async def _translate_all(
    client: httpx.AsyncClient, chunks: List[List[str]], tl: str,
    sem: Optional[asyncio.Semaphore] = None,
) -> Optional[List[List[str]]]:
    sem = sem or asyncio.Semaphore(CHUNK_CONCURRENCY)
    results = await asyncio.gather(*(_translate_chunk(client, sem, c, tl) for c in chunks))
    if any(r is None for r in results):
        return None
    return list(results)


async def translate_chapter_text(client: httpx.AsyncClient, text: str, tl: str = "vi") -> Optional[str]:
    """Dịch cả chương. None nếu còn khối nào không dịch được sau khi thử lại."""
    paragraphs = split_paragraphs(text)
    if not paragraphs:
        return ""
    chunks = build_chunks(paragraphs)
    lang = (tl or "vi").lower()

    if lang in ("bilingual", "song ngữ", "song ngu"):
        return await _translate_bilingual(client, chunks)

    target = "en" if lang in ("en", "english") else "vi"
    translated = await _translate_all(client, chunks, target)
    if translated is None:
        logger.warning("Dịch dự phòng Google thất bại ở ít nhất một khối của chương.")
        return None
    return "\n\n".join(line for chunk in translated for line in chunk)


async def _translate_bilingual(client: httpx.AsyncClient, chunks: List[List[str]]) -> Optional[str]:
    """Cặp [EN]/[VI] theo từng đoạn; lệch dòng ở bất kỳ khối nào thì trả bản tiếng Việt."""
    sem = asyncio.Semaphore(CHUNK_CONCURRENCY)  # dùng chung: hai ngôn ngữ vẫn chỉ CHUNK_CONCURRENCY request cùng lúc
    en_res, vi_res = await asyncio.gather(
        _translate_all(client, chunks, "en", sem), _translate_all(client, chunks, "vi", sem)
    )
    if vi_res is None:
        return None
    aligned = en_res is not None and all(
        len(src) == len(en) == len(vi) for src, en, vi in zip(chunks, en_res, vi_res)
    )
    if not aligned:
        return "\n\n".join(line for chunk in vi_res for line in chunk)
    pairs = [
        f"[EN] {en_line}\n[VI] {vi_line}"
        for en_chunk, vi_chunk in zip(en_res, vi_res)
        for en_line, vi_line in zip(en_chunk, vi_chunk)
    ]
    return "\n\n".join(pairs)
