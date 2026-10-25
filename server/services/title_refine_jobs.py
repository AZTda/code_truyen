"""How one queued title is refined by Hy-MT2: term hints from Names, notes kept out of the model, output checks."""

from typing import List, Optional, Sequence

from services.title_dict import title_dict
from services.title_dict_text import cap_first
from services.title_mt_client import title_mt
from services.title_mt_prompts import dedup_hints
from services.title_mt_validate import book_case
from services.title_routing import note_vi, split_note, term_hints

# Mỗi dòng gợi ý tốn ~10 token xử lý prompt (~0,4 s trên MiniPC 3 nhân): chỉ giữ vài thuật ngữ.
MAX_ITEM_HINTS = 8


def _vn_check():
    d = title_dict.get()
    return d.is_vn_syllable if d is not None else None


def chapter_parts(src: str):
    """(phần cho mô hình dịch, hậu tố tiếng Việt dịch sẵn bằng từ điển cho ghi chú cuối trong ngoặc)."""
    d = title_dict.get()
    main, note = split_note(src)
    if not note or d is None:
        return src, ""
    return main, f" ({note_vi(d, note)})"


async def refine_one(kind: str, src: str) -> Optional[str]:
    d = title_dict.get()
    if kind == "book":
        vi = await title_mt.zh_to_vi(src, term_hints(d, src)[:MAX_ITEM_HINTS], vn_check=_vn_check())
        return book_case(vi) if vi else None
    if kind == "chapter":
        main, suffix = chapter_parts(src)
        vi = await title_mt.zh_to_vi(main, term_hints(d, main)[:MAX_ITEM_HINTS], vn_check=_vn_check())
        return cap_first(vi) + suffix if vi else None
    return None


async def refine_chapters(srcs: Sequence[str]) -> List[Optional[str]]:
    """Một lô tựa chương (đánh số, kiểm từng mục, mục hỏng dịch lại riêng lẻ)."""
    d = title_dict.get()
    parts = [chapter_parts(s) for s in srcs]
    mains = [m for m, _ in parts]
    item_hints = [term_hints(d, m)[:MAX_ITEM_HINTS] for m in mains]
    outs = await title_mt.zh_to_vi_batch(mains, dedup_hints(item_hints), item_hints, vn_check=_vn_check())
    return [cap_first(vi) + suffix if vi else None for vi, (_, suffix) in zip(outs, parts)]
