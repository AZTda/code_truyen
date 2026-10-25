import re
from typing import List, Optional


def _dictionary():
    """Từ điển convert nếu đã sẵn sàng, ngược lại None (không bao giờ ném lỗi)."""
    try:
        from services.title_dict import title_dict
        return title_dict.get()
    except Exception:
        return None


def has_cjk(text: str) -> bool:
    """Kiểm tra chuỗi có chứa chữ Hán hay không — helper dùng chung toàn server."""
    return any('一' <= ch <= '鿿' for ch in text or "")


def generate_cjk_variants(text: str) -> List[str]:
    """Sinh các biến thể từ khóa tiếng Trung (có/không dấu câu, khoảng trắng)."""
    clean = (text or "").strip()
    if not clean:
        return []
    variants = [clean]
    no_punct = re.sub(r"[，、,._\-\s《》]+", "", clean).strip()
    if no_punct and no_punct not in variants:
        variants.append(no_punct)
    spaced = re.sub(r"[，、,._\-]+", " ", clean).strip()
    spaced = re.sub(r"\s+", " ", spaced)
    if spaced and spaced not in variants:
        variants.append(spaced)
    return variants


from services.hanviet_char_map import HANVIET_CHAR_MAP  # noqa: E402,F401 (re-export)


def transliterate_to_hanviet(text: str) -> str:
    """Phiên âm Hán-Việt viết hoa từng chữ. Có từ điển convert (data/dict) thì dùng nó: đủ ~17 nghìn chữ và ưu tiên
    tên riêng nhiều chữ trong Names; chưa có (đang tải/dựng, hoặc lỗi) thì dùng bảng nhỏ bên dưới như trước."""
    if not text:
        return ""
    d = _dictionary()
    if d is not None:
        try:
            return d.hanviet(text.strip().replace("《", "").replace("》", "").strip())
        except Exception:
            pass
    return legacy_transliterate(text)


def legacy_transliterate(text: str) -> str:
    """Phiên âm bằng bảng HANVIET_CHAR_MAP nhỏ (chữ ngoài bảng giữ nguyên chữ Hán)."""
    if not text:
        return ""
    
    # Strip common Chinese quotes
    clean = text.strip().replace("《", "").replace("》", "").strip()
    
    words = []
    current_non_cjk = []
    
    for ch in clean:
        if '\u4e00' <= ch <= '\u9fff':
            if current_non_cjk:
                words.append("".join(current_non_cjk).strip())
                current_non_cjk = []
            
            vi = HANVIET_CHAR_MAP.get(ch)
            if vi:
                words.append(vi)
            else:
                # If character is not in common map, keep it
                words.append(ch)
        else:
            if ch in "，、,._-":
                if current_non_cjk:
                    words.append("".join(current_non_cjk).strip())
                    current_non_cjk = []
                words.append(",")
            elif ch.isspace():
                if current_non_cjk:
                    words.append("".join(current_non_cjk).strip())
                    current_non_cjk = []
            else:
                current_non_cjk.append(ch)
                
    if current_non_cjk:
        words.append("".join(current_non_cjk).strip())
        
    result = " ".join([w for w in words if w])
    result = re.sub(r"\s*,\s*", ", ", result).strip().strip(",")
    return result


import unicodedata


def convert_chapter_subtitle(sub: str) -> str:
    """Tựa chương tức thì: bản mô hình đã tinh chỉnh (cache) > convert từ điển > phiên âm bảng nhỏ."""
    if not sub:
        return ""
    try:
        from services.title_cache_store import title_cache
        refined = title_cache.refined("chapter", sub)
        if refined:
            return refined
        d = _dictionary()
        if d is not None:
            from services.title_routing import chapter_dict
            return chapter_dict(d, sub)[0]
    except Exception:
        pass
    return legacy_transliterate(sub)


def strip_vietnamese_accents(text: str) -> str:
    """Loại bỏ dấu tiếng Việt để so khớp âm chuẩn xác."""
    nfkd = unicodedata.normalize('NFKD', text)
    return ''.join([c for c in nfkd if not unicodedata.combining(c)]).replace('đ', 'd').replace('Đ', 'D').lower().strip()


from services.hanviet_char_map import REVERSE_HANVIET_OVERRIDE  # noqa: E402

_BUILT_REVERSE_MAP = {}
for _cjk, _vi in HANVIET_CHAR_MAP.items():
    _key = strip_vietnamese_accents(_vi)
    if _key and _key not in _BUILT_REVERSE_MAP:
        _BUILT_REVERSE_MAP[_key] = _cjk

_BUILT_REVERSE_MAP.update(REVERSE_HANVIET_OVERRIDE)


def reverse_hanviet_candidates(text: str, k: int = 4) -> List[str]:
    """Các ứng viên chữ Hán cho một từ khoá Hán-Việt (tốt nhất trước); [] khi không giống âm Hán-Việt."""
    d = _dictionary()
    if d is None:
        legacy = legacy_reverse_query(text)
        return [legacy] if legacy else []
    try:
        from services.title_dict_reverse import profile, query_candidates
        if not profile(d, text).looks_hanviet:
            return []
        return [c for c in query_candidates(d, text, k) if len(c) >= 2]
    except Exception:
        return []


def reverse_hanviet_query(text: str) -> Optional[str]:
    """Chuyển đổi tức thì từ khóa tiếng Việt sang chữ Hán (< 1 ms) không cần gọi AI."""
    if not text:
        return None
    if _dictionary() is not None:
        cands = reverse_hanviet_candidates(text, 1)
        return cands[0] if cands else None
    return legacy_reverse_query(text)


def legacy_reverse_query(text: str) -> Optional[str]:
    """Phiên âm ngược bằng bảng nhỏ (dùng khi chưa có từ điển convert)."""
    if not text:
        return None
    clean = re.sub(r"[^\w\s]", " ", text).strip()
    words = [strip_vietnamese_accents(w) for w in clean.split() if w.strip()]
    if not words:
        return None

    chars = []
    i = 0
    n = len(words)
    while i < n:
        if i + 2 < n:
            triplet = f"{words[i]} {words[i+1]} {words[i+2]}"
            if triplet in _BUILT_REVERSE_MAP:
                chars.append(_BUILT_REVERSE_MAP[triplet])
                i += 3
                continue
        if i + 1 < n:
            pair = f"{words[i]} {words[i+1]}"
            if pair in _BUILT_REVERSE_MAP:
                chars.append(_BUILT_REVERSE_MAP[pair])
                i += 2
                continue
        w = words[i]
        # Từ nối tiếng Việt được ưu tiên BỎ QUA trước khi tra map — tránh
        # phiên âm nhầm ("là" -> 罗, "trong" -> 重) tạo chuỗi Hán vô nghĩa.
        if w in {"cua", "va", "la", "o", "trong", "ve", "cho"}:
            i += 1
        elif w in _BUILT_REVERSE_MAP:
            chars.append(_BUILT_REVERSE_MAP[w])
            i += 1
        else:
            return None

    res = "".join(chars)
    return res if len(res) >= 2 else None
