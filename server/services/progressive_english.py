"""Progressive English Reading Service (Diglot Weave).

Substitutes mature vocabulary learned by the reader into Vietnamese chapter text,
enabling gradual, natural immersion (25% / 50% / 75% / 100% ratios).
"""

import re
import logging
from typing import List, Dict, Any

logger = logging.getLogger("truyencloud.progressive")


def _clean_vietnamese_meanings(meaning_vi: str) -> List[str]:
    """Splits a definition string like 'cổ xưa, cổ kính (adj); đồ cổ' into searchable phrases."""
    cleaned = re.sub(r"\([^)]*\)", "", meaning_vi)
    raw_parts = re.split(r"[,;/|•\n]", cleaned)
    results = []
    for p in raw_parts:
        s = p.strip().lower()
        if len(s) >= 2 and not s.startswith("to ") and not s.startswith("được "):
            results.append(s)
    return results


class ProgressiveEnglishService:
    """Implements progressive bilingual text weaving (Diglot Weave)."""

    def weave_chapter(
        self,
        paragraphs: List[str],
        mature_words: List[Dict[str, Any]],
        target_ratio: float = 0.5,
    ) -> Dict[str, Any]:
        """Transforms a chapter's paragraphs by replacing Vietnamese words

        with their English counterparts based on user's mature vocabulary.

        Args:
            paragraphs: List of Vietnamese paragraph strings.
            mature_words: List of dicts, each with keys 'word' (English)
                          and 'meaning_vi' (Vietnamese translation).
            target_ratio: Replacement frequency ratio between 0.0 and 1.0.

        Returns:
            Dict containing transformed paragraphs, replaced count, and glossary.
        """
        if not paragraphs or not mature_words or target_ratio <= 0:
            return {
                "paragraphs": paragraphs,
                "replaced_count": 0,
                "target_ratio": target_ratio,
                "ratio_achieved": 0.0,
                "glossary": [],
            }

        # Build mapping: vietnamese_phrase -> english_word (cụm dài khớp trước)
        phrase_to_eng: Dict[str, str] = {}
        for item in mature_words:
            eng = item.get("word", "").strip()
            vi_raw = item.get("meaning_vi", "") or item.get("context_meaning_vi", "")
            if not eng or not vi_raw:
                continue
            for phrase in _clean_vietnamese_meanings(vi_raw):
                phrase_to_eng.setdefault(phrase, eng)

        if not phrase_to_eng:
            return {
                "paragraphs": paragraphs,
                "replaced_count": 0,
                "total_candidates": 0,
                "target_ratio": target_ratio,
                "ratio_achieved": 0.0,
                "glossary": [],
            }

        # MỘT regex gộp mọi cụm: mỗi vị trí trong văn bản chỉ được tính là một ứng viên.
        # Chạy từng cụm riêng thì cụm ngắn ("kiếm") khớp lại bên trong cụm dài vừa bị bỏ
        # qua ("thanh kiếm"), đếm trùng ứng viên và làm lệch tỉ lệ.
        ordered = sorted(phrase_to_eng, key=len, reverse=True)
        pattern = re.compile(r"\b(?:" + "|".join(re.escape(p) for p in ordered) + r")\b", re.IGNORECASE)

        ratio = max(0.0, min(1.0, target_ratio))
        total_candidates = 0
        transformed_paragraphs: List[str] = []
        glossary_counts: Dict[str, Dict[str, Any]] = {}

        def replacer(m: "re.Match") -> str:
            nonlocal total_candidates
            total_candidates += 1
            # Tích luỹ kiểu Bresenham: sau k ứng viên đã thay đúng floor(k * ratio) cụm,
            # nên 75% thật sự là 3/4 (bản cũ step=int(1/0.75)=1 biến 75% thành 100%).
            if int(total_candidates * ratio + 1e-9) == int((total_candidates - 1) * ratio + 1e-9):
                return m.group(0)
            matched_str = m.group(0)
            phrase = matched_str.lower()
            eng = phrase_to_eng.get(phrase)
            if not eng:
                return matched_str
            # Match capitalization: Title Case or UPPERCASE
            if matched_str.isupper() and len(eng) > 1:
                sub = eng.upper()
            elif matched_str[0].isupper():
                sub = eng.capitalize()
            else:
                sub = eng.lower()
            entry = glossary_counts.setdefault(eng, {"english": eng, "vietnamese": phrase, "count": 0})
            entry["count"] += 1
            return sub

        for para in paragraphs:
            transformed_paragraphs.append(pattern.sub(replacer, para))

        replaced_count = sum(item["count"] for item in glossary_counts.values())
        glossary_list = sorted(
            glossary_counts.values(), key=lambda x: x["count"], reverse=True
        )

        ratio_achieved = (
            round(replaced_count / total_candidates, 2)
            if total_candidates > 0
            else 0.0
        )

        return {
            "paragraphs": transformed_paragraphs,
            "replaced_count": replaced_count,
            "total_candidates": total_candidates,
            "target_ratio": target_ratio,
            "ratio_achieved": ratio_achieved,
            "glossary": glossary_list,
        }


progressive_service = ProgressiveEnglishService()
