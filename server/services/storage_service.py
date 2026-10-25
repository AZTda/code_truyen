import os
import json
import hashlib
import logging
import tempfile
import threading
from typing import Optional, Dict, Any, List
from datetime import datetime, timezone

logger = logging.getLogger("truyencloud.storage")

# Root directory for novel data storage
DATA_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data")
NOVELS_DIR = os.path.join(DATA_DIR, "novels")

class StorageService:
    """Service to persist novels and translated chapters on server disk storage.
    
    Structure:
      server/data/novels/{novel_id}/
        info.json
        chapters/
          chapter_1.json
          chapter_2.json
    """

    def __init__(self, base_dir: str = NOVELS_DIR):
        self.base_dir = base_dir
        self._write_lock = threading.RLock()
        os.makedirs(self.base_dir, exist_ok=True)

    def _write_json_atomic(self, path: str, data: Any, durable: bool = True) -> None:
        """Write JSON via replace so a killed worker cannot leave a partial file.

        durable=False bỏ fsync từng tệp (dùng cho ghi hàng loạt chương khi nhập
        truyện — vẫn atomic qua os.replace, nhanh hơn hàng chục lần).
        """
        directory = os.path.dirname(path)
        fd, temp_path = tempfile.mkstemp(prefix=".truyencloud-", suffix=".tmp", dir=directory)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(data, handle, ensure_ascii=False, indent=2)
                handle.flush()
                if durable:
                    os.fsync(handle.fileno())
            os.replace(temp_path, path)
        finally:
            if os.path.exists(temp_path):
                os.unlink(temp_path)

    def get_novel_dir(self, novel_id: str) -> str:
        """Returns the folder path for a specific novel."""
        raw_id = str(novel_id or "").strip()
        if not raw_id:
            raise ValueError("novel_id không được để trống.")

        # Keep existing simple IDs readable, but hash unsafe IDs instead of
        # stripping characters. Stripping made `a/b` collide with `ab` and could
        # silently mix two novels in the same directory.
        if len(raw_id) <= 160 and all(c.isalnum() or c in ("-", "_") for c in raw_id):
            storage_id = raw_id
        else:
            storage_id = "id_" + hashlib.sha256(raw_id.encode("utf-8")).hexdigest()[:32]
        return os.path.join(self.base_dir, storage_id)

    def get_chapters_dir(self, novel_id: str) -> str:
        """Returns the chapters folder path for a novel."""
        return os.path.join(self.get_novel_dir(novel_id), "chapters")

    def ensure_novel_dir(self, novel_id: str) -> str:
        """Creates the novel folder and chapters subfolder if they do not exist."""
        chapters_dir = self.get_chapters_dir(novel_id)
        os.makedirs(chapters_dir, exist_ok=True)
        return self.get_novel_dir(novel_id)

    def save_novel_info(self, novel_id: str, novel_data: Dict[str, Any]) -> str:
        """Saves or updates novel metadata into info.json."""
        self.ensure_novel_dir(novel_id)
        info_path = os.path.join(self.get_novel_dir(novel_id), "info.json")

        existing = self.get_novel_info(novel_id) or {}
        existing.update(novel_data)
        existing["id"] = novel_id
        existing["updated_at"] = datetime.now(timezone.utc).isoformat()
        if "created_at" not in existing:
            existing["created_at"] = datetime.now(timezone.utc).isoformat()

        try:
            with self._write_lock:
                self._write_json_atomic(info_path, existing)
            logger.info(f"Đã lưu metadata truyện '{novel_id}' vào {info_path}")
        except Exception as e:
            logger.error(f"Lỗi khi lưu info.json cho truyện {novel_id}: {e}")

        # Báo cho index tìm kiếm trong bộ nhớ biết thư viện đã thay đổi
        try:
            from services.library_index import library_index
            library_index.invalidate()
        except Exception:
            pass
        return info_path

    def get_novel_info(self, novel_id: str) -> Optional[Dict[str, Any]]:
        """Reads novel metadata from info.json."""
        info_path = os.path.join(self.get_novel_dir(novel_id), "info.json")
        if not os.path.exists(info_path):
            return None
        try:
            with open(info_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception as e:
            logger.error(f"Lỗi đọc info.json của truyện {novel_id}: {e}")
            return None

    def get_chapter_path(self, novel_id: str, chapter_number: int) -> str:
        """Returns path to a chapter file."""
        if not isinstance(chapter_number, int) or chapter_number < 1:
            raise ValueError("chapter_number phải là số nguyên dương.")
        return os.path.join(self.get_chapters_dir(novel_id), f"chapter_{chapter_number}.json")

    def save_chapter(self, novel_id: str, chapter_number: int, chapter_data: Dict[str, Any]) -> str:
        """Saves chapter content (original and/or translated) to disk."""
        self.ensure_novel_dir(novel_id)
        chapter_path = self.get_chapter_path(novel_id, chapter_number)

        existing = self.get_chapter(novel_id, chapter_number) or {}
        existing.update(chapter_data)
        existing["novel_id"] = novel_id
        existing["chapter_number"] = chapter_number
        existing["updated_at"] = datetime.now(timezone.utc).isoformat()

        try:
            with self._write_lock:
                self._write_json_atomic(chapter_path, existing)
            logger.info(f"Đã lưu chương {chapter_number} của truyện '{novel_id}' vào {chapter_path}")
        except Exception as e:
            logger.error(f"Lỗi khi lưu chapter_{chapter_number}.json cho truyện {novel_id}: {e}")
        return chapter_path

    def save_chapters_bulk(self, novel_id: str, chapters: List[Dict[str, Any]]) -> int:
        """Ghi hàng loạt chương trong một lượt: chỉ đọc-gộp khi tệp đã tồn tại
        (giữ nguyên bản dịch cũ), bỏ fsync từng tệp. Trả về số chương đã ghi."""
        self.ensure_novel_dir(novel_id)
        now = datetime.now(timezone.utc).isoformat()
        saved = 0
        with self._write_lock:
            for ch in chapters:
                ch_num = ch.get("chapter_number")
                if not isinstance(ch_num, int) or ch_num < 1:
                    continue
                chapter_path = self.get_chapter_path(novel_id, ch_num)
                data = ch
                if os.path.exists(chapter_path):
                    existing = self.get_chapter(novel_id, ch_num) or {}
                    existing.update(ch)
                    data = existing
                data["novel_id"] = novel_id
                data["chapter_number"] = ch_num
                data["updated_at"] = now
                try:
                    self._write_json_atomic(chapter_path, data, durable=False)
                    saved += 1
                except Exception as e:
                    logger.error(f"Lỗi ghi bulk chương {ch_num} truyện {novel_id}: {e}")
        if saved:
            logger.info(f"Đã ghi hàng loạt {saved} chương cho truyện '{novel_id}'.")
        return saved

    def get_chapter(self, novel_id: str, chapter_number: int) -> Optional[Dict[str, Any]]:
        """Reads chapter content from disk."""
        chapter_path = self.get_chapter_path(novel_id, chapter_number)
        if not os.path.exists(chapter_path):
            return None
        try:
            with open(chapter_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception as e:
            logger.error(f"Lỗi đọc chương {chapter_number} của truyện {novel_id}: {e}")
            return None

    def list_all_novels(self) -> List[Dict[str, Any]]:
        """Lists all novels currently stored on the server disk."""
        novels = []
        if not os.path.exists(self.base_dir):
            return novels
        for folder_name in os.listdir(self.base_dir):
            folder_path = os.path.join(self.base_dir, folder_name)
            if os.path.isdir(folder_path):
                info = self.get_novel_info(folder_name)
                if info:
                    novels.append(info)
        return novels

    def get_chapters_index_path(self, novel_id: str) -> str:
        """Returns path to the chapters_index.json file."""
        return os.path.join(self.get_novel_dir(novel_id), "chapters_index.json")

    def save_chapters_index(self, novel_id: str, chapters: List[Dict[str, Any]]) -> None:
        """Saves a lightweight index of all chapters for fast listing."""
        self.ensure_novel_dir(novel_id)
        index_path = self.get_chapters_index_path(novel_id)
        try:
            with self._write_lock:
                self._write_json_atomic(index_path, chapters)
        except Exception as e:
            logger.error(f"Lỗi khi lưu chapters_index.json cho truyện {novel_id}: {e}")

    def list_chapters(self, novel_id: str) -> List[Dict[str, Any]]:
        """Lists all chapters for a novel using fast chapters_index.json with disk fallback."""
        index_path = self.get_chapters_index_path(novel_id)
        if os.path.exists(index_path):
            try:
                with open(index_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    if isinstance(data, list) and data:
                        return data
            except Exception:
                pass

        # Fallback to scanning chapters directory
        chapters_dir = self.get_chapters_dir(novel_id)
        if not os.path.exists(chapters_dir):
            return []
        chapters = []
        for filename in os.listdir(chapters_dir):
            if filename.startswith("chapter_") and filename.endswith(".json"):
                full_path = os.path.join(chapters_dir, filename)
                try:
                    with open(full_path, "r", encoding="utf-8") as f:
                        data = json.load(f)
                        # Keep only lightweight summary keys for listing
                        chapters.append({
                            "chapter_number": data.get("chapter_number"),
                            "id": data.get("id", str(data.get("chapter_number"))),
                            "title": data.get("title", f"Chương {data.get('chapter_number')}"),
                            "title_vi": data.get("title_vi", ""),
                            "url": data.get("url", "")
                        })
                except Exception:
                    pass
        chapters.sort(key=lambda x: x.get("chapter_number", 0))
        if chapters:
            self.save_chapters_index(novel_id, chapters)
        return chapters

    def sync_all_to_database(self, db_service) -> None:
        """Preloads all stored novels and chapters into DatabaseService on startup."""
        stored_novels = self.list_all_novels()
        if not stored_novels:
            return

        try:
            existing_res = db_service.table("novels").select("id").execute()
            if existing_res.data and len(existing_res.data) >= len(stored_novels):
                logger.info("Database đã có đầy đủ truyện và chương, bỏ qua sync khởi động.")
                return
        except Exception:
            pass

        for novel in stored_novels:
            db_service.table("novels").upsert(novel).execute()
            chapters = self.list_chapters(novel["id"])
            chapter_records = []
            for ch in chapters:
                rec = {
                    "novel_id": ch.get("novel_id", novel["id"]),
                    "chapter_number": ch.get("chapter_number"),
                    "title": ch.get("title", f"Chương {ch.get('chapter_number')}"),
                    "title_vi": ch.get("title_vi", ""),
                    "url": ch.get("url", ""),
                }
                if ch.get("original_content"):
                    rec["original_content"] = ch["original_content"]
                if ch.get("translated_content"):
                    rec["translated_content"] = ch["translated_content"]
                if ch.get("translation_engine"):
                    rec["translation_engine"] = ch["translation_engine"]
                chapter_records.append(rec)
            table_ch = db_service.table("chapters")
            if hasattr(table_ch, "batch_upsert"):
                table_ch.batch_upsert(chapter_records)
            else:
                for cr in chapter_records:
                    table_ch.upsert(cr).execute()
        logger.info(f"Đã đồng bộ {len(stored_novels)} truyện từ thư mục đĩa vào cơ sở dữ liệu.")

storage_service = StorageService()
