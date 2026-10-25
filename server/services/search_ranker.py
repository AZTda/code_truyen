"""Chấm điểm và xếp hạng kết quả tìm kiếm song ngữ Việt/Trung.

Điểm mỗi truyện = mức tương đồng cao nhất giữa từ khóa và các "danh tính" của
truyện: tựa tiếng Việt, PHIÊN ÂM HÁN-VIỆT của tựa gốc tiếng Trung, tựa gốc,
và tác giả. Nhờ đối chiếu ngược qua Hán-Việt, từ khóa tiếng Việt xếp đúng
truyện Trung lên đầu kể cả khi bản dịch máy của từ khóa không hoàn hảo.
"""

from typing import List, Optional, Sequence
from models.novel import Novel
from .hanviet_dict import transliterate_to_hanviet, has_cjk
from .query_lexicon import _ACCENTED_MAP, _FOLDED_MAP, fuzzy_lookup_latin
from .text_similarity import strip_accents, similarity_vi, similarity_cjk, to_simplified

# Dưới ngưỡng này, kết quả web bị coi là "độ tin cậy thấp" — kích hoạt vòng
# AI dịch lại tên gốc chạy nền để tự sửa cho lần tìm sau.
LOW_CONFIDENCE = 0.45
# Kết quả web khớp chắc chắn từ STRONG_MATCH trở lên; khi đã có kết quả như vậy thì các kết quả dưới WEAK_FLOOR bị bỏ
STRONG_MATCH = 0.8
WEAK_FLOOR = 0.35


def score_novel(
    raw_query: str, novel: Novel, cjk_terms: Optional[Sequence[str]] = None
) -> float:
    """Điểm liên quan 0..1 của một truyện đối với từ khóa (Việt, Anh hoặc Trung)."""
    query = (raw_query or "").strip().lower()
    if not query:
        return 0.0

    title = to_simplified((novel.title or "").strip())
    title_vi = (novel.title_vi or "").strip()

    if has_cjk(query):
        # Từ khóa tiếng Trung: so thẳng với tựa gốc; nếu truyện local chỉ có
        # tựa Việt thì không so được — trả theo phần Latin (nếu có).
        score = similarity_cjk(query, title)
        latin_part = strip_accents("".join(ch for ch in query if not ('一' <= ch <= '鿿')).strip())
        if latin_part and len(latin_part) >= 3:
            score = max(score, similarity_vi(latin_part, strip_accents(title_vi)))
        return score

    q_no_acc = strip_accents(query)
    # Mỗi ứng viên so sánh kèm TRỌNG SỐ: phiên âm Hán-Việt một phần (từ điển
    # không phủ hết chữ) vẫn được chấm điểm, chỉ giảm theo tỷ lệ chữ đã map —
    # thay vì bị vứt bỏ khiến back-check "chết im lặng" với nhiều tựa Trung.
    candidates: List[tuple] = []
    if title_vi and not has_cjk(title_vi):
        candidates.append((strip_accents(title_vi), 1.0))
    if title:
        if has_cjk(title):
            hv = transliterate_to_hanviet(title)
            if hv:
                total_cjk = sum(1 for c in title if '一' <= c <= '鿿')
                leftover = sum(1 for c in hv if '一' <= c <= '鿿')
                mapped_ratio = 1.0 - (leftover / total_cjk) if total_cjk else 0.0
                if leftover == 0:
                    candidates.append((strip_accents(hv), 1.0))
                elif mapped_ratio >= 0.6:
                    hv_clean = " ".join(
                        "".join(c for c in hv if not ('一' <= c <= '鿿')).split()
                    )
                    if hv_clean:
                        candidates.append((strip_accents(hv_clean), mapped_ratio))
        else:
            candidates.append((strip_accents(title), 1.0))
    author = str(novel.author or "").strip()
    if author and not has_cjk(author):
        candidates.append((strip_accents(author), 1.0))

    best = max((similarity_vi(q_no_acc, c) * w for c, w in candidates if c), default=0.0)

    # Đối chiếu trực tiếp các từ khoá tiếng Trung đã dịch (hoặc từ điển tên riêng/võng văn) với tựa gốc `title`.
    # Nhờ đó khi tìm 'MIT', 'havard', 'marvel', 'bắt đầu đánh dấu', truyện có tựa gốc '哈佛', '漫威', '开局签到'
    # vẫn đạt điểm cao ngay cả khi `title_vi` tạm thời là âm Hán-Việt ('Cáp Phật', 'Mạn Uy').
    if title and has_cjk(title):
        effective_cjk = list(cjk_terms) if cjk_terms else list(
            _ACCENTED_MAP.get(query) or _FOLDED_MAP.get(q_no_acc) or fuzzy_lookup_latin(q_no_acc) or ()
        )
        for idx, cjk_q in enumerate(effective_cjk[:4]):
            cjk_norm = to_simplified((cjk_q or "").strip())
            if cjk_norm and has_cjk(cjk_norm):
                weight = 0.95 if idx == 0 else max(0.80, 0.95 - idx * 0.05)
                best = max(best, similarity_cjk(cjk_norm, title) * weight)

    return best


def rank_results(
    raw_query: str, novels: List[Novel], cjk_terms: Optional[Sequence[str]] = None
) -> List[Novel]:
    """Sắp xếp: truyện local trước, trong mỗi nhóm theo điểm liên quan giảm dần.

    Sắp xếp ổn định — hai kết quả cùng điểm giữ nguyên thứ tự nguồn trả về.
    """
    decorated = [
        (0 if n.source_id == "local" else 1, -score_novel(raw_query, n, cjk_terms), idx, n)
        for idx, n in enumerate(novels)
    ]
    decorated.sort(key=lambda x: (x[0], x[1], x[2]))
    return [item[3] for item in decorated]


def top_web_score(
    raw_query: str, novels: List[Novel], cjk_terms: Optional[Sequence[str]] = None
) -> Optional[float]:
    """Điểm cao nhất trong các kết quả TỪ WEB; None nếu không có kết quả web."""
    scores = [score_novel(raw_query, n, cjk_terms) for n in novels if n.source_id != "local"]
    return max(scores) if scores else None


def drop_weak_web_results(
    raw_query: str, novels: List[Novel], cjk_terms: Optional[Sequence[str]] = None
) -> List[Novel]:
    """Bỏ kết quả web không liên quan khi đã có kết quả khớp chắc chắn; không có thì giữ nguyên.

    Ví dụ tìm "剑来": có "剑来（1…" khớp chắc chắn thì "借剑", "斗罗绝世…" bị bỏ. Truy vấn lạ
    không có kết quả khớp nào thì trả đủ danh sách như trước, để không mất kết quả đúng duy nhất.
    Truyện trong thư viện riêng (local) không bao giờ bị bỏ.
    """
    scores = {id(n): score_novel(raw_query, n, cjk_terms) for n in novels if n.source_id != "local"}
    has_local = any(n.source_id == "local" for n in novels)
    if not has_local and not any(s >= STRONG_MATCH for s in scores.values()):
        return novels
    return [n for n in novels if n.source_id == "local" or scores[id(n)] >= WEAK_FLOOR]
