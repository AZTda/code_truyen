"""So sánh độ tương đồng văn bản song ngữ Việt/Trung — module lá, thuần hàm.

Tiếng Việt: điểm 0..1 từ ba tín hiệu (khớp chuỗi con, bao phủ từ có nghĩa,
Dice trên bigram ký tự) trên chuỗi đã bỏ dấu — chịu được thiếu dấu, sai chính
tả nhẹ và đảo trật tự từ. Tiếng Trung: khớp chuỗi con + tỷ lệ ký tự chung.
"""

import unicodedata
from typing import Set

try:  # Phồn thể -> giản thể: uukanshu/piaotia viết phồn thể, truy vấn người dùng thường là giản thể
    import zhconv
except ImportError:  # pragma: no cover - thiếu gói thì vẫn chạy, chỉ kém khớp chữ phồn thể
    zhconv = None

VI_STOPWORDS = {"cua", "va", "la", "o", "trong", "ve", "cho", "cua", "mot", "nhung"}


def to_simplified(text: str) -> str:
    """Chuyển chữ phồn thể sang giản thể để so khớp (劍來 -> 剑来); giữ nguyên nếu thiếu thư viện."""
    if not text or zhconv is None:
        return text or ""
    return zhconv.convert(text, "zh-cn")


def strip_accents(text: str) -> str:
    """Bỏ dấu tiếng Việt + hạ chữ thường: 'Kiếm Lai' -> 'kiem lai'."""
    nfkd = unicodedata.normalize('NFKD', text or "")
    return ''.join(c for c in nfkd if not unicodedata.combining(c)).replace('đ', 'd').replace('Đ', 'D').lower()


def char_bigrams(text: str) -> Set[str]:
    """Bigram ký tự theo từng từ (không vắt ngang khoảng trắng)."""
    grams: Set[str] = set()
    for word in (text or "").split():
        if len(word) == 1:
            grams.add(word)
        else:
            for i in range(len(word) - 1):
                grams.add(word[i:i + 2])
    return grams


def dice(a: Set[str], b: Set[str]) -> float:
    if not a or not b:
        return 0.0
    return 2.0 * len(a & b) / (len(a) + len(b))


def similarity_vi(query_no_acc: str, target_no_acc: str) -> float:
    """Độ tương đồng 0..1 giữa hai chuỗi tiếng Việt ĐÃ bỏ dấu, hạ chữ thường."""
    q = " ".join((query_no_acc or "").split())
    t = " ".join((target_no_acc or "").split())
    if not q or not t:
        return 0.0
    if q == t:
        return 1.0

    score = dice(char_bigrams(q), char_bigrams(t))

    # Khớp chuỗi con — bỏ qua khi cả từ khóa chỉ là từ nối ("trong", "cua"...)
    if q not in VI_STOPWORDS:
        if f" {q} " in f" {t} " and len(q) >= 4:
            # Trùng trọn từ/cụm từ: điểm cao
            score = max(score, 0.92)
        elif f" {q}" in f" {t}":
            # Bắt đầu tại đầu một từ = đang gõ dở ("kie" -> "kiem lai"): tỷ lệ
            # theo độ dài. Chuỗi con GIỮA từ ("an" trong "nhan") không được thưởng.
            score = max(score, 0.6 + 0.32 * len(q) / len(t))
        elif t in q:
            score = max(score, max(0.5, 0.85 * len(t) / len(q)))

    # Bao phủ từ: người dùng gõ đủ các từ chính nhưng sai trật tự/thiếu dấu câu.
    # Từ khóa toàn từ nối thì không áp dụng (tránh "trong" khớp mọi tựa).
    q_words = {w for w in q.split() if w not in VI_STOPWORDS}
    t_words = set(t.split())
    if q_words:
        coverage = len(q_words & t_words) / len(q_words)
        if coverage == 1.0 and len(q_words) >= 2:
            # Phạt khi tựa dài hơn nhiều so với từ khóa (tập con của tựa dài)
            denom = max(len(q_words), min(len(t_words), len(q_words) + 2))
            score = max(score, 0.9 * len(q_words) / denom)
        else:
            score = max(score, coverage * 0.62)

    # 1.0 chỉ dành cho chuỗi giống hệt nhau
    return min(score, 0.95)


def similarity_cjk(query: str, target: str) -> float:
    """Độ tương đồng 0..1 giữa hai chuỗi tiếng Trung (chỉ xét ký tự CJK)."""
    q = "".join(ch for ch in to_simplified(query or "") if '一' <= ch <= '鿿')
    t = "".join(ch for ch in to_simplified(target or "") if '一' <= ch <= '鿿')
    if not q or not t:
        return 0.0
    if q == t:
        return 1.0
    if q in t:
        return 0.93 if len(q) >= 2 else 0.6
    if t in q:
        return 0.88

    # Tỷ lệ ký tự chung theo từ khóa, phạt tựa dài hơn nhiều (tránh tựa dài
    # tình cờ chứa vài chữ trùng lọt ngưỡng) + thưởng cặp ký tự liền kề chung
    q_set, t_set = set(q), set(t)
    overlap = len(q_set & t_set) / len(q_set)
    if len(t_set) > len(q_set):
        overlap *= (len(q_set) / len(t_set)) ** 0.5
    q_pairs = {q[i:i + 2] for i in range(len(q) - 1)}
    t_pairs = {t[i:i + 2] for i in range(len(t) - 1)}
    pair_bonus = dice(q_pairs, t_pairs) * 0.35
    return min(overlap * 0.55 + pair_bonus, 0.85)
