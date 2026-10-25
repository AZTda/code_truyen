"""Kho bảng thuật ngữ theo từng bộ truyện, phía máy chủ.

Nguồn sự thật của bảng thuật ngữ nằm ở ứng dụng (bảng `term_memories` trên máy
người dùng) và được gửi kèm mỗi lời gọi dịch. Nhưng máy chủ còn hai đường dịch
KHÔNG xuất phát từ lời gọi ấy:

  * `routes/chapters.py` — tự dịch ngay khi vừa cào xong một chương;
  * `services/auto_updater.py` — dịch trước chương mới phát hiện, chạy ngầm.

Hai đường đó không có ứng dụng nào ở đầu bên kia để gửi bảng thuật ngữ lên, nên
nếu không lưu lại thì tên nhân vật vẫn trôi đúng như cũ ở những chương được dịch
sẵn. Kho này giữ lại bảng thuật ngữ gần nhất của mỗi truyện để hai đường ấy dùng.

Kho còn giữ tên riêng do AI tự trích sau mỗi chương dịch xong (`auto=True`,
xem services/auto_glossary.py). Mất file thì mục của ứng dụng được gửi lại ở lời
gọi dịch kế tiếp, còn mục auto sẽ được trích lại dần theo các chương mới.

Kho là của CẢ TRUYỆN: bảng này được áp lên chữ Hán của mọi chương rồi bản dịch lưu thành bản
chung cho mọi độc giả. Vì vậy chỉ quản trị viên (routes/translate.py) và auto_glossary được ghi
vào đây, và mục từ lời gọi phải qua `validate_persistable`. Thuật ngữ của tài khoản thường chỉ áp
cho bản dịch của chính họ (`merged_for_request`), và bản dịch dùng thuật ngữ mà kho không có
(`diverges_from_store`) không được lưu thành bản chung.
"""

import json
import logging
import os
import threading
from typing import Any, Dict, List, Optional, Tuple

from models.translate import TermItem
from services.translation_validation import cjk_count

logger = logging.getLogger(__name__)

DATA_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data")
GLOSSARY_DIR = os.path.join(DATA_DIR, "glossaries")

# Chặn trên cho một truyện. Bảng thuật ngữ đi thẳng vào prompt nên nó chiếm chỗ
# của chính nội dung chương; ứng dụng đã cắt còn 120 mục trước khi gửi.
MAX_TERMS_PER_NOVEL = 400
# Bảng thuật ngữ dùng chung cho mọi độc giả của truyện: chặn cỡ mỗi lần gửi và độ
# dài từng mục để một lời gọi không nhồi được prompt hay đầu độc cả bảng.
MAX_TERMS_PER_REQUEST = 200
MAX_SOURCE_CHARS = 32
MAX_TARGET_CHARS = 80
# Mục được GHI vào kho dùng chung (không áp cho riêng một lời gọi): xem validate_persistable
MIN_SOURCE_CJK = 2
MIN_TARGET_CHARS = 2


def _clean_pair(item: Any) -> Optional[Tuple[str, str]]:
    source = str(getattr(item, "source", "") or "").strip()
    target = str(getattr(item, "target", "") or "").strip()
    if not source or not target or source == target:
        return None
    if len(source) > MAX_SOURCE_CHARS or len(target) > MAX_TARGET_CHARS:
        return None
    if "\n" in source or "\n" in target:
        return None
    return source, target


def sanitize(terms: Optional[List[Any]]) -> List[TermItem]:
    """Bảng thuật ngữ của MỘT lời gọi sau khi chặn cỡ: tối đa 200 mục, nguồn <= 32 / đích <= 80 ký tự.

    Bảng này đi thẳng vào prompt AI, nên mọi đường nhận bảng từ client (kể cả khi
    không ghi vào kho) đều phải qua đây.
    """
    cleaned = (_clean_pair(t) for t in (terms or [])[:MAX_TERMS_PER_REQUEST])
    return [TermItem(source=p[0], target=p[1]) for p in cleaned if p]


def validate_persistable(terms: Optional[List[Any]], original: Optional[str]) -> List[TermItem]:
    """Phần của bảng một lời gọi được phép vào kho DÙNG CHUNG của truyện (chỉ quản trị viên mới ghi).

    Kho này được áp bằng str.replace mù lên chữ Hán của mọi chương trước khi AI thấy, nên mỗi mục phải
    đúng là một tên riêng thật: nguồn xuất hiện nguyên văn trong bản gốc, có >= 2 chữ Hán (một chữ như
    "的" hay "林" làm hỏng mọi từ ghép như "森林") và đích >= 2 ký tự.
    """
    text = original or ""
    return [
        t for t in sanitize(terms)
        if cjk_count(t.source) >= MIN_SOURCE_CJK and len(t.target) >= MIN_TARGET_CHARS and t.source in text
    ]


class GlossaryStore:
    def __init__(self, base_dir: str = GLOSSARY_DIR):
        self.base_dir = base_dir
        self._lock = threading.Lock()
        os.makedirs(self.base_dir, exist_ok=True)

    def _path_for(self, novel_id: str) -> Optional[str]:
        # novel_id đi thẳng vào đường dẫn file: chặn mọi ký tự có thể thoát ra
        # khỏi thư mục (".." hay dấu phân cách) thay vì tin vào dữ liệu gọi tới.
        safe = "".join(c for c in str(novel_id) if c.isalnum() or c in ("-", "_"))
        if not safe:
            return None
        return os.path.join(self.base_dir, f"{safe}.json")

    def _read_raw_entries(self, novel_id: str) -> List[Dict[str, Any]]:
        path = self._path_for(novel_id)
        if not path or not os.path.exists(path):
            return []
        try:
            with open(path, "r", encoding="utf-8") as f:
                raw = json.load(f)
            return [
                {"source": item["source"], "target": item["target"], "auto": bool(item.get("auto", False))}
                for item in raw.get("terms", [])
                if item.get("source") and item.get("target")
            ]
        except Exception:
            logger.warning("Khong doc duoc bang thuat ngu cua truyen %s", novel_id, exc_info=True)
            return []

    def list_entries(self, novel_id: str) -> List[Dict[str, Any]]:
        """Các mục {source, target, auto}, nguồn DÀI trước.

        Từ DÀI trước từ NGẮN: nếu "麻省" chạy trước "麻省理工" thì tên trường bị
        ăn mất một nửa và phần còn lại thành chuỗi vô nghĩa.
        """
        entries = self._read_raw_entries(novel_id)
        entries.sort(key=lambda e: len(e["source"]), reverse=True)
        return entries

    def load(self, novel_id: str) -> List[TermItem]:
        """Bảng thuật ngữ đã biết của một truyện. Không có thì trả về danh sách rỗng."""
        return [TermItem(source=e["source"], target=e["target"]) for e in self.list_entries(novel_id)]

    def save(self, novel_id: str, terms: List[Any], auto: bool = False) -> int:
        """Gộp bảng thuật ngữ mới vào bảng đã lưu. Trả về tổng số mục sau khi gộp.

        Gộp chứ không ghi đè: ứng dụng chỉ gửi lên phần đầu bảng đã cắt bớt, nên
        ghi đè sẽ làm rụng dần những thuật ngữ ít tra nhưng vẫn cần. Mục auto=True
        (AI tự trích) không bao giờ đè mục đã có; mục người dùng thì đè mục auto.
        """
        path = self._path_for(novel_id)
        if not path or not terms:
            return 0
        pairs = [(t.source, t.target) for t in sanitize(terms)]
        if not pairs:
            return 0

        with self._lock:
            merged: Dict[str, Dict[str, Any]] = {e["source"]: e for e in self._read_raw_entries(novel_id)}
            for source, target in pairs:
                if auto and source in merged:
                    if merged[source].get("auto", False):
                        entry = merged.pop(source)
                        merged[source] = entry
                    continue
                merged.pop(source, None)
                merged[source] = {"source": source, "target": target, "auto": auto}

            if len(merged) > MAX_TERMS_PER_NOVEL:
                # Giữ toàn bộ thuật ngữ thủ công; với thuật ngữ auto=True thì ưu tiên giữ các mục mới nhất
                manual = [e for e in merged.values() if not e.get("auto", False)]
                auto_entries = [e for e in merged.values() if e.get("auto", False)]
                if len(manual) >= MAX_TERMS_PER_NOVEL:
                    kept = sorted(manual, key=lambda e: -len(e["source"]))[:MAX_TERMS_PER_NOVEL]
                else:
                    remaining = MAX_TERMS_PER_NOVEL - len(manual)
                    kept = manual + auto_entries[-remaining:]
                merged = {e["source"]: e for e in kept}

            payload = {"novel_id": novel_id, "terms": list(merged.values())}
            tmp = f"{path}.tmp"
            try:
                with open(tmp, "w", encoding="utf-8") as f:
                    json.dump(payload, f, ensure_ascii=False, indent=2)
                # Đổi tên là thao tác nguyên tử: một lần ghi hỏng không để lại
                # file JSON cụt mà những lần đọc sau không phân tích được.
                os.replace(tmp, path)
            except Exception:
                logger.warning("Khong ghi duoc bang thuat ngu cua truyen %s", novel_id, exc_info=True)
                try:
                    os.remove(tmp)
                except OSError:
                    pass
                return 0

        return len(merged)

    def merged_for_request(self, novel_id: str, request_terms: Optional[List[TermItem]]) -> List[TermItem]:
        """Bảng đã lưu (gồm mục AI tự trích) phủ thêm bảng của lời gọi — lời gọi thắng."""
        combined: Dict[str, str] = {t.source: t.target for t in self.load(novel_id)}
        for item in sanitize(request_terms):
            combined[item.source] = item.target
        ordered = sorted(combined.items(), key=lambda kv: len(kv[0]), reverse=True)
        return [TermItem(source=s, target=t) for s, t in ordered[:MAX_TERMS_PER_NOVEL]]

    def diverges_from_store(self, novel_id: str, request_terms: Optional[List[TermItem]], content: str) -> bool:
        """Lời gọi mang thuật ngữ ảnh hưởng tới CHƯƠNG NÀY (có trong nội dung) mà kho dùng chung không có đúng như vậy.

        Kết quả dịch với những thuật ngữ đó là "bản riêng": trả cho người gọi nhưng không được ghi thành
        bản dùng chung cho mọi độc giả.
        """
        relevant = [t for t in (request_terms or []) if t.source and t.source in content]
        if not relevant:
            return False
        stored = {e["source"]: e["target"] for e in self.list_entries(novel_id)}
        return any(stored.get(t.source) != t.target for t in relevant)

    def count(self, novel_id: str) -> int:
        return len(self.list_entries(novel_id))

    def replace_all(self, novel_id: str, terms: List[Any]) -> int:
        """Thay thế toàn bộ bảng thuật ngữ của `novel_id` (do quản trị viên chỉnh sửa)."""
        path = self._path_for(novel_id)
        if not path:
            return 0
        pairs = [(t.source, t.target) for t in sanitize(terms)]
        entries = [
            {"source": src, "target": tgt, "auto": False}
            for src, tgt in pairs[:MAX_TERMS_PER_NOVEL]
        ]
        with self._lock:
            payload = {"novel_id": novel_id, "terms": entries}
            tmp = f"{path}.tmp"
            try:
                with open(tmp, "w", encoding="utf-8") as f:
                    json.dump(payload, f, ensure_ascii=False, indent=2)
                os.replace(tmp, path)
            except Exception:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
                return 0
        return len(entries)


glossary_store = GlossaryStore()
