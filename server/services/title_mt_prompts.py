"""Hy-MT2 prompt builders (vendor templates from the model card) for the llama.cpp chat endpoint."""

from typing import Iterable, List, Sequence, Tuple

CHAT_PATH = "/v1/chat/completions"
ZH2VI, VI2ZH = "zh2vi", "vi2zh"
_TARGET = {ZH2VI: "越南语", VI2ZH: "中文"}
# Tham số khuyến nghị của Tencent trừ nhiệt độ: tựa đề cần kết quả ổn định (greedy) để cache dùng lại được.
SAMPLING = {"temperature": 0, "top_k": 1, "repeat_penalty": 1.05, "cache_prompt": True}
MAX_HINTS = 25


def hint_block(hints: Iterable[Tuple[str, str]]) -> str:
    rows = [f"{zh} 翻译成 {vi}" for zh, vi in hints]
    return "参考下面的翻译：\n" + "\n".join(rows) + "\n\n" if rows else ""


def instruction(direction: str) -> str:
    return f"将以下文本翻译为{_TARGET[direction]}，注意只需要输出翻译后的结果，不要额外解释："


def batch_instruction(direction: str) -> str:
    return f"将以下文本逐行翻译为{_TARGET[direction]}，每行保留行首编号，注意只需要输出翻译后的结果，不要额外解释："


def numbered(texts: Sequence[str]) -> str:
    return "\n".join(f"{i + 1}. {t}" for i, t in enumerate(texts))


def dedup_hints(lists: Iterable[Iterable[Tuple[str, str]]], cap: int = MAX_HINTS) -> List[Tuple[str, str]]:
    seen, out = set(), []
    for hints in lists:
        for zh, vi in hints:
            if zh not in seen:
                seen.add(zh)
                out.append((zh, vi))
    return out[:cap]


def single_body(text: str, direction: str, hints: Sequence[Tuple[str, str]] = (), max_tokens: int = 96) -> dict:
    msg = hint_block(hints) + instruction(direction) + "\n\n" + text
    return {"messages": [{"role": "user", "content": msg}], "max_tokens": max_tokens, **SAMPLING}


def batch_body(texts: Sequence[str], direction: str, hints: Sequence[Tuple[str, str]] = ()) -> dict:
    msg = hint_block(hints) + batch_instruction(direction) + "\n\n" + numbered(texts)
    return {"messages": [{"role": "user", "content": msg}], "max_tokens": 32 * len(texts) + 32, **SAMPLING}
