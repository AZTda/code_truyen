"""Xếp hạng "Dành cho bạn" của tab Khám phá. Thuần hàm, không I/O.

Chỉ dùng tủ sách của CHÍNH người gọi và số đếm ẩn danh từ người khác. Không hàm nào ở đây
nhận hay trả id người dùng khác ra ngoài: phần việc cần id (tìm người cùng gu) nằm trọn trong
`co_reader_counts` và chỉ cho ra con số, và chỉ những con số do từ MIN_CO_READERS người trở lên.

Điểm của một truyện công khai chưa có trong tủ:
    W_AUTHOR    * (cùng tác giả với một truyện trong tủ)
  + W_CO_READER * (số người cùng gu cũng giữ truyện này, tối đa CO_READER_CAP; chỉ tính từ MIN_CO_READERS người)
  + W_SOURCE    * (cùng nguồn với phần lớn tủ sách)
Xếp theo điểm giảm dần, rồi độ mới, rồi id nên kết quả lặp lại được. Độ mới cố ý chỉ làm tiêu chí phân
hạng hoà chứ không thành số hạng của điểm: nó giống hệt nhau với mọi tài khoản nên cho nó lấn tín hiệu
riêng thì danh sách các tài khoản lại giống nhau.
"""

from collections import Counter
from dataclasses import dataclass
from functools import lru_cache
from typing import AbstractSet, Any, Callable, Dict, FrozenSet, Iterable, List, Mapping, Sequence, Tuple

from services.text_similarity import strip_accents

# ── Trọng số: giữ thứ bậc tác giả > người cùng gu > nguồn ───────────────────
W_AUTHOR = 10.0
W_CO_READER = 2.0
# 4 x 2.0 = 8 < W_AUTHOR: đám đông không bao giờ vượt được một truyện cùng tác giả.
CO_READER_CAP = 4
W_SOURCE = 1.0
# Nguồn chỉ tính khi chiếm từ ngần này tủ sách; một truyện lẻ không làm thành "nguồn bạn hay đọc".
SOURCE_MIN_SHARE = 0.25
# Trần chi phí mỗi lượt: chỉ xét chừng này người cùng gu, ưu tiên người trùng nhiều truyện nhất.
MAX_NEIGHBOURS = 200
# Một truyện chỉ được tính "người cùng gu" khi có ít nhất chừng này người cùng gu cùng giữ. Với 1 người thì con số
# CHÍNH LÀ tủ sách của người đó: tài khoản dò chỉ cần nạp một truyện hiếm (đồng bộ tiến độ không kiểm id truyện)
# là đọc được cả tủ của người giữ truyện hiếm ấy. Lý do "Người cùng gu..." cũng nói số nhiều.
MIN_CO_READERS = 2
# Một "tác giả" đứng tên chừng này truyện công khai trở lên gần như chắc chắn là tên giữ chỗ (tên nguồn cào, kể cả
# bản trang chi tiết đã dịch sang tiếng Việt) chứ không phải một người, nên không cho làm tín hiệu "cùng tác giả".
MAX_NOVELS_PER_AUTHOR = 12

REASON_AUTHOR = "Cùng tác giả với truyện bạn đang đọc"
REASON_CO_READERS = "Người cùng gu với bạn cũng đang đọc"
REASON_SOURCE = "Cùng nguồn bạn hay đọc"

_EDGE_PUNCT = " .,;:!?()[]{}<>\"'`-_/\\|"
# Giá trị các nguồn cào / nạp tệp điền khi không biết tác giả (đã bỏ dấu, hạ chữ thường).
_UNKNOWN_AUTHORS = frozenset({
    "unknown", "unknown author", "anonymous", "none", "null", "n/a",
    "khong ro", "khong ro tac gia", "chua ro", "dang cap nhat", "khuyet danh", "vo danh",
    "khong xac dinh", "chua biet", "an danh", "vi tri", "vo danh thi", "bat tuong",  # bản dịch/Hán-Việt của các chữ Hán dưới
    "未知", "佚名", "不详", "无名氏", "匿名",
    # Tên nguồn mà scraper_*.py điền vào `author` khi không đọc được tác giả: cả một nguồn sẽ thành "cùng tác giả".
    "69书吧", "晋江文学城", "uu看书", "飘天文学",
})

# (entry của library_index, dict truyện, độ mới tính bằng ms): đúng dạng `discover_novels` đã dựng.
Candidate = Tuple[Dict[str, Any], Dict[str, Any], int]
Ranked = Tuple[Dict[str, Any], Dict[str, Any], str]


@dataclass(frozen=True)
class Taste:
    """Gu của MỘT người, suy ra từ chính tủ sách của họ."""
    authors: FrozenSet[str] = frozenset()
    sources: FrozenSet[str] = frozenset()


@lru_cache(maxsize=8192)
def _fold(text: str) -> str:
    return " ".join(strip_accents(text).split()).strip(_EDGE_PUNCT)


def author_key(novel: Mapping[str, Any]) -> str:
    """Tên tác giả chuẩn hoá để so khớp ('' khi không biết).

    Bỏ dấu và hạ chữ thường để 'Thần Đông' khớp 'than dong'. Tên giữ chỗ của nguồn cào và trường hợp
    scraper gán nhầm tên truyện vào tác giả đều coi như không có, kẻo mọi truyện thiếu tác giả "cùng tác giả".
    """
    key = _fold(str(novel.get("author") or ""))
    if not key or key in _UNKNOWN_AUTHORS:
        return ""
    if key in (_fold(str(novel.get("title") or "")), _fold(str(novel.get("title_vi") or ""))):
        return ""
    return key


def taste_of(novels: Iterable[Mapping[str, Any]], is_private: Callable[[Mapping[str, Any]], bool]) -> Taste:
    """Gu từ các truyện trong tủ. Truyện tự nạp (riêng tư) vẫn góp tác giả nhưng không góp nguồn."""
    authors = set()
    sources: Counter = Counter()
    for novel in novels:
        key = author_key(novel)
        if key:
            authors.add(key)
        source = str(novel.get("source_id") or "")
        if source and not is_private(novel):
            sources[source] += 1
    total = sum(sources.values())
    often = frozenset(name for name, count in sources.items() if count / total >= SOURCE_MIN_SHARE)
    return Taste(frozenset(authors), often)


def co_reader_counts(
    owned: AbstractSet[str],
    user_id: str,
    libraries: Mapping[str, AbstractSet[str]],
    keepers: Mapping[str, AbstractSet[str]],
    public_ids: AbstractSet[str],
) -> Dict[str, int]:
    """Số người cùng gu đang giữ từng truyện công khai chưa có trong tủ của `user_id`.

    Người cùng gu = người khác giữ ít nhất một truyện CÔNG KHAI trùng với tủ này. Truyện riêng tư
    không bao giờ nối hai người với nhau và không được đếm. Kết quả chỉ là {id truyện: số người},
    và chỉ gồm truyện có từ MIN_CO_READERS người trở lên: một con số 1 là tủ sách của đúng một người.
    """
    overlap: Dict[str, int] = {}
    for novel_id in owned & public_ids:
        for other in keepers.get(novel_id, ()):
            if other != user_id:
                overlap[other] = overlap.get(other, 0) + 1
    closest = sorted(overlap, key=lambda other: (-overlap[other], other))[:MAX_NEIGHBOURS]
    counts: Dict[str, int] = {}
    for other in closest:
        for novel_id in libraries.get(other, ()):
            if novel_id in public_ids and novel_id not in owned:
                counts[novel_id] = counts.get(novel_id, 0) + 1
    return {novel_id: n for novel_id, n in counts.items() if n >= MIN_CO_READERS}


def _novel_id(entry: Mapping[str, Any], novel: Mapping[str, Any]) -> str:
    return str(entry.get("id") or novel.get("id") or "")


def rank_for_you(
    candidates: Sequence[Candidate],
    owned: AbstractSet[str],
    taste: Taste,
    co_counts: Mapping[str, int],
) -> List[Ranked]:
    """Bỏ truyện đã có trong tủ, chấm điểm phần còn lại rồi trả (entry, novel, lý do) theo thứ tự hiển thị."""
    keyed = [(author_key(novel), entry, novel, recency) for entry, novel, recency in candidates]
    crowded = {key for key, n in Counter(key for key, *_ in keyed if key).items() if n > MAX_NOVELS_PER_AUTHOR}
    scored = []
    for key, entry, novel, recency in keyed:
        novel_id = _novel_id(entry, novel)
        if novel_id in owned:
            continue
        by_author = bool(key) and key in taste.authors and key not in crowded
        co_readers = co_counts.get(novel_id, 0)
        by_source = str(novel.get("source_id") or "") in taste.sources
        score = (
            (W_AUTHOR if by_author else 0.0)
            + W_CO_READER * min(co_readers, CO_READER_CAP)
            + (W_SOURCE if by_source else 0.0)
        )
        reason = (
            REASON_AUTHOR if by_author
            else REASON_CO_READERS if co_readers > 0
            else REASON_SOURCE if by_source
            else ""
        )
        scored.append((-round(score, 6), -recency, novel_id, entry, novel, reason))
    scored.sort(key=lambda row: row[:3])
    return [(entry, novel, reason) for *_, entry, novel, reason in scored]
