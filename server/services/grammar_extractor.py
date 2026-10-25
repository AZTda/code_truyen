"""Grammar Pattern Recognition Service.

Detects advanced literary & academic English grammar patterns in novels/sentences,
maintains occurrence frequency, and persists example contexts in database.
"""

import re
import time
import logging
from typing import List, Dict, Any

from database import db
from services.grammar_rules import GRAMMAR_RULES

logger = logging.getLogger("truyencloud.grammar")

# Giới hạn một lượt trích xuất: văn bản do người dùng gửi, mỗi mẫu là một regex và mỗi lần ghi là 2 thao tác SQLite
# đồng bộ. Câu dài hơn ngưỡng này (văn bản không dấu câu) bị bỏ qua thay vì quét.
MAX_SENTENCE_CHARS = 1000
MAX_DETECTIONS_PER_REQUEST = 100

class GrammarPatternService:
    """Service to scan, store, and report on English grammar patterns."""

    def __init__(self):
        self.table_name = "grammar_patterns"

    def _get_table(self):
        return db.table(self.table_name)

    def scan_sentence(
        self,
        sentence: str,
        novel_id: str = "",
        chapter_number: int = 0,
        novel_title: str = "",
    ) -> List[Dict[str, Any]]:
        """Scans a single English sentence against the grammar rules catalog."""
        clean = sentence.strip()
        if len(clean) < 10 or len(clean) > MAX_SENTENCE_CHARS:
            return []

        matched: List[Dict[str, Any]] = []
        for rule in GRAMMAR_RULES:
            if rule["regex"].search(clean):
                matched.append({
                    "pattern_id": rule["id"],
                    "pattern": rule["pattern"],
                    "pattern_name": rule["pattern_name"],
                    "category": rule["category"],
                    "explanation_vi": rule["explanation_vi"],
                    "matched_sentence": clean,
                    "novel_id": novel_id,
                    "chapter_number": chapter_number,
                    "novel_title": novel_title,
                })
        return matched

    @staticmethod
    def _row_id(user_id: str, pattern_id: str) -> str:
        # Thống kê theo TỪNG người học: trước đây mọi tài khoản cộng dồn vào một hàng.
        return f"{user_id}:{pattern_id}"

    def record_patterns(
        self,
        patterns: List[Dict[str, Any]],
        user_id: str = "local",
    ) -> int:
        """Persists detected patterns to SQLite/Supabase, updating frequency and example pool."""
        if not patterns:
            return 0

        now = int(time.time())
        recorded_count = 0
        table = self._get_table()

        for p in patterns:
            pid = self._row_id(user_id, p["pattern_id"])
            existing_res = table.select("*").eq("id", pid).execute()
            existing_list = existing_res.data if existing_res else []
            existing = existing_list[0] if existing_list else None

            example_item = {
                "sentence": p["matched_sentence"],
                "novel_id": p.get("novel_id", ""),
                "novel_title": p.get("novel_title", ""),
                "chapter_number": p.get("chapter_number", 0),
                "recorded_at": now,
            }

            if existing:
                examples = existing.get("examples", [])
                # Avoid duplicate identical sentences
                if not any(e.get("sentence") == example_item["sentence"] for e in examples):
                    examples.append(example_item)
                    if len(examples) > 10:
                        examples = examples[-10:]  # keep recent 10 examples

                new_freq = existing.get("frequency", 0) + 1
                table.upsert({
                    "id": pid,
                    "user_id": user_id,
                    "pattern_id": p["pattern_id"],
                    "pattern": p["pattern"],
                    "pattern_name": p["pattern_name"],
                    "category": p["category"],
                    "explanation_vi": p["explanation_vi"],
                    "frequency": new_freq,
                    "examples": examples,
                    "last_seen_at": now,
                }).execute()
            else:
                table.upsert({
                    "id": pid,
                    "user_id": user_id,
                    "pattern_id": p["pattern_id"],
                    "pattern": p["pattern"],
                    "pattern_name": p["pattern_name"],
                    "category": p["category"],
                    "explanation_vi": p["explanation_vi"],
                    "frequency": 1,
                    "examples": [example_item],
                    "first_seen_at": now,
                    "last_seen_at": now,
                }).execute()

            recorded_count += 1

        return recorded_count

    def detect_patterns(
        self,
        text: str,
        novel_id: str = "",
        chapter_number: int = 0,
        novel_title: str = "",
    ) -> List[Dict[str, Any]]:
        """Tách câu và phát hiện mẫu ngữ pháp. THUẦN (không chạm CSDL) nên chạy được ở luồng phụ.

        Tối đa MAX_DETECTIONS_PER_REQUEST kết quả; câu dài hơn MAX_SENTENCE_CHARS bị bỏ qua.
        """
        # Simple English sentence boundary regex
        raw_sentences = re.split(r"(?<=[.!?])\s+", text)
        all_detected: List[Dict[str, Any]] = []

        for s in raw_sentences:
            s_clean = s.strip()
            if not s_clean:
                continue
            matches = self.scan_sentence(
                sentence=s_clean,
                novel_id=novel_id,
                chapter_number=chapter_number,
                novel_title=novel_title,
            )
            all_detected.extend(matches)
            if len(all_detected) >= MAX_DETECTIONS_PER_REQUEST:
                return all_detected[:MAX_DETECTIONS_PER_REQUEST]

        return all_detected

    def extract_from_text(
        self,
        text: str,
        novel_id: str = "",
        chapter_number: int = 0,
        novel_title: str = "",
        auto_record: bool = True,
        user_id: str = "local",
    ) -> List[Dict[str, Any]]:
        """Splits text into sentences and detects grammar patterns."""
        all_detected = self.detect_patterns(text, novel_id, chapter_number, novel_title)

        if auto_record and all_detected:
            self.record_patterns(all_detected, user_id=user_id)

        return all_detected

    def get_stats(self, limit: int = 15, user_id: str = "local") -> Dict[str, Any]:
        """Returns top recurring grammar patterns with frequency and example sentences."""
        table = self._get_table()
        res = table.select("*").eq("user_id", user_id).execute()
        # "id" trả về cho ứng dụng vẫn là mã mẫu ngữ pháp, không phải khoá hàng nội bộ
        rows = [{**r, "id": r.get("pattern_id") or r.get("id")} for r in (res.data if res and res.data else [])]

        # Sort descending by frequency
        rows.sort(key=lambda r: r.get("frequency", 0), reverse=True)
        top = rows[:limit]

        # Aggregate summary
        total_occurrences = sum(r.get("frequency", 0) for r in rows)
        categories = {}
        for r in rows:
            cat = r.get("category", "General")
            categories[cat] = categories.get(cat, 0) + r.get("frequency", 0)

        # Ensure default catalogue items are visible even before encounters
        if len(top) < len(GRAMMAR_RULES):
            encountered_ids = {r.get("id") for r in rows}
            for rule in GRAMMAR_RULES:
                if rule["id"] not in encountered_ids:
                    top.append({
                        "id": rule["id"],
                        "pattern": rule["pattern"],
                        "pattern_name": rule["pattern_name"],
                        "category": rule["category"],
                        "explanation_vi": rule["explanation_vi"],
                        "frequency": 0,
                        "examples": [],
                        "last_seen_at": 0,
                    })
                    if len(top) >= limit:
                        break

        return {
            "total_patterns_cataloged": len(GRAMMAR_RULES),
            "total_patterns_encountered": len(rows),
            "total_occurrences": total_occurrences,
            "category_distribution": categories,
            "patterns": top,
        }


grammar_service = GrammarPatternService()
