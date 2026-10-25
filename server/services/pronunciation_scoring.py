"""Transcript-vs-target scoring for pronunciation practice.

The app sends what platform speech-to-text actually HEARD. Similarity blends a
letter edit distance with a coarse English phonetic key, so a homophone
spelling ("effemeral") scores close to the target while a different word
("funeral") does not. Single-word targets are aligned letter by letter,
multi-word targets word by word.
"""

import re
import unicodedata
from functools import lru_cache
from typing import Any, Callable, Dict, List, Sequence, Tuple

PHONETIC_WEIGHT = 0.6
_EXTRA_WORD_COST = 0.5
# Chặn input khổng lồ: căn chỉnh là O(n*m) và mỗi ô lại tốn hai lần so khớp chữ O(k*k) bằng Python thuần.
# 60 token x 48 ký tự từng chiếm ~2,4 giây CPU cho một request ~6 KB; từ/cụm luyện phát âm thật chỉ vài từ.
_MAX_TOKENS = 15
_MAX_TOKEN_CHARS = 24

_PHONETIC_RULES: Tuple[Tuple[str, str], ...] = (
    (r"^kn", "n"), (r"^wr", "r"), (r"^ps", "s"), (r"^x", "s"),
    (r"tch", "ch"), (r"ph", "f"), (r"ck", "k"), (r"qu", "kw"), (r"wh", "w"),
    (r"dg", "j"), (r"c(?=[eiy])", "s"), (r"c", "k"), (r"q", "k"), (r"x", "ks"),
    (r"(?<!^)y", "i"), (r"[aeiou]+", "a"), (r"(.)\1+", r"\1"),
)


def normalize_text(text: str) -> str:
    """Lowercase ASCII words only: accents, apostrophes and punctuation removed."""
    decomposed = unicodedata.normalize("NFKD", text or "")
    plain = "".join(c for c in decomposed if not unicodedata.combining(c)).lower()
    plain = re.sub(r"['’`]", "", plain)
    return re.sub(r"[^a-z0-9]+", " ", plain).strip()


@lru_cache(maxsize=4096)
def phonetic_key(token: str) -> str:
    key = token
    if len(key) > 3:
        key = re.sub(r"(?<=[^aeiouy])e$", "", key)  # silent final e
    for pattern, repl in _PHONETIC_RULES:
        key = re.sub(pattern, repl, key)
    return key


def _align(a: Sequence[Any], b: Sequence[Any], sub_cost: Callable[[Any, Any], float],
           del_cost: float = 1.0, ins_cost: float = 1.0) -> Tuple[float, List[Tuple[str, int, int]]]:
    """Weighted edit distance plus the op path: ('match'|'sub', i, j), ('del', i, -1), ('ins', -1, j)."""
    n, m = len(a), len(b)
    dp = [[0.0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        dp[i][0] = i * del_cost
    for j in range(1, m + 1):
        dp[0][j] = j * ins_cost
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            dp[i][j] = min(dp[i - 1][j - 1] + sub_cost(a[i - 1], b[j - 1]),
                           dp[i - 1][j] + del_cost, dp[i][j - 1] + ins_cost)
    ops: List[Tuple[str, int, int]] = []
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and dp[i][j] == dp[i - 1][j - 1] + sub_cost(a[i - 1], b[j - 1]):
            ops.append(("match" if a[i - 1] == b[j - 1] else "sub", i - 1, j - 1))
            i, j = i - 1, j - 1
        elif i > 0 and dp[i][j] == dp[i - 1][j] + del_cost:
            ops.append(("del", i - 1, -1))
            i -= 1
        else:
            ops.append(("ins", -1, j - 1))
            j -= 1
    ops.reverse()
    return dp[n][m], ops


def _edit_distance(a: str, b: str) -> int:
    """Levenshtein chỉ lấy khoảng cách (hai hàng), không dựng ma trận + đường đi như _align."""
    if len(a) < len(b):
        a, b = b, a
    previous = list(range(len(b) + 1))
    for i, char_a in enumerate(a, 1):
        current = [i]
        for j, char_b in enumerate(b, 1):
            current.append(min(previous[j - 1] + (char_a != char_b), previous[j] + 1, current[j - 1] + 1))
        previous = current
    return previous[-1]


def _ratio(a: str, b: str) -> float:
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    return max(0.0, 1.0 - _edit_distance(a, b) / max(len(a), len(b)))


@lru_cache(maxsize=8192)
def token_similarity(target: str, heard: str) -> float:
    if target == heard:
        return 1.0
    if not target or not heard:
        return 0.0
    phonetic = _ratio(phonetic_key(target), phonetic_key(heard))
    return PHONETIC_WEIGHT * phonetic + (1 - PHONETIC_WEIGHT) * _ratio(target, heard)


def status_for(score: int) -> str:
    return "correct" if score >= 90 else ("acceptable" if score >= 65 else "improve")


def _letter_segments(target: str, heard: str) -> List[Dict[str, Any]]:
    # Khác chữ nhưng cùng khoá phát âm (ph/f, c/k...) là lỗi chính tả của bộ
    # nhận giọng, không phải lỗi phát âm -> chỉ đánh dấu "acceptable".
    homophone = phonetic_key(target) == phonetic_key(heard)
    _, ops = _align(target, heard, lambda x, y: 0.0 if x == y else 1.0)
    segments = []
    for op, i, j in ops:
        if op == "ins":
            continue
        heard_char = heard[j] if j >= 0 else ""
        if op == "match":
            segments.append({"symbol": target[i], "label": target[i], "heard": heard_char,
                             "score": 100, "status": "correct", "op": "match"})
        elif op == "sub":
            segments.append({"symbol": target[i], "label": f"nghe thành '{heard_char}'",
                             "heard": heard_char, "score": 70 if homophone else 0,
                             "status": "acceptable" if homophone else "improve", "op": "sub"})
        else:
            segments.append({"symbol": target[i], "label": "thiếu âm", "heard": "",
                             "score": 0, "status": "improve", "op": "missing"})
    return segments


def _score_single(target: str, heard_tokens: List[str]) -> Dict[str, Any]:
    candidates = list(heard_tokens)
    if len(heard_tokens) > 1:
        candidates.append("".join(heard_tokens)[:_MAX_TOKEN_CHARS])
    best = max(candidates, key=lambda h: token_similarity(target, h))
    return {"similarity": token_similarity(target, best), "heard": best,
            "unit": "letter", "segments": _letter_segments(target, best)}


def _score_multi(targets: List[str], heard_tokens: List[str]) -> Dict[str, Any]:
    cost, ops = _align(targets, heard_tokens, lambda t, h: 1.0 - token_similarity(t, h),
                       del_cost=1.0, ins_cost=_EXTRA_WORD_COST)
    segments = []
    for op, i, j in ops:
        if op == "ins":
            continue
        if op == "del":
            segments.append({"symbol": targets[i], "label": "(không nghe thấy)", "heard": "",
                             "score": 0, "status": "improve", "op": "missing"})
            continue
        sim = int(round(token_similarity(targets[i], heard_tokens[j]) * 100))
        segments.append({"symbol": targets[i], "label": heard_tokens[j], "heard": heard_tokens[j],
                         "score": sim, "status": status_for(sim), "op": op})
    return {"similarity": max(0.0, 1.0 - cost / len(targets)), "heard": " ".join(heard_tokens),
            "unit": "word", "segments": segments}


def score_transcript(target_text: str, transcript: str) -> Dict[str, Any]:
    """Score a recognized transcript against the target; both must be non-empty after normalization."""
    targets = [t[:_MAX_TOKEN_CHARS] for t in normalize_text(target_text).split()[:_MAX_TOKENS]]
    heard_tokens = [h[:_MAX_TOKEN_CHARS] for h in normalize_text(transcript).split()[:_MAX_TOKENS]]
    if len(targets) == 1:
        result = _score_single(targets[0], heard_tokens)
    else:
        result = _score_multi(targets, heard_tokens)
    result["score"] = int(round(result["similarity"] * 100))
    return result
