import os
import posixpath
import re
import uuid
import asyncio
import zipfile
import logging
from datetime import datetime, timezone
from xml.etree import ElementTree as ET
from typing import Dict, Any, List, Optional, Tuple
from database import db
from services.storage_service import storage_service
from services.translation_worker import translation_worker
from services.cover_service import cover_service
from services.hanviet_dict import has_cjk

logger = logging.getLogger("truyencloud.import_service")

# Regex for chapter titles in Vietnamese and Chinese
CHAPTER_PATTERNS = [
    # Chinese chapter patterns: 第1章, 第一百二十章, 第一卷, etc.
    re.compile(r'^\s*(第[0-9一二三四五六七八九十百千万]+[章节回卷部集篇])\s*(.*)$', re.MULTILINE),
    # Vietnamese chapter patterns: Chương 1, Hồi 2, Quyển 3, Tiết 4, etc.
    re.compile(r'^\s*(Chương|Hồi|Quyển|Phần|Tiết|Mục)\s+([0-9]+|[IVXLCDM]+)\s*[:\-\.]?\s*(.*)$', re.IGNORECASE | re.MULTILINE),
    # Fallback numbered chapters: 1., 2., 3., Chapter 1
    re.compile(r'^\s*(Chapter|Ch\.)\s+([0-9]+)\s*[:\-\.]?\s*(.*)$', re.IGNORECASE | re.MULTILINE),
]

MAX_IMPORTED_CHAPTERS = 20_000
MAX_CHAPTER_CHARS = 1_000_000
MAX_IMPORTED_TEXT_CHARS = 12_000_000
MAX_EPUB_ENTRIES = 10_000
MAX_EPUB_UNCOMPRESSED_BYTES = 50 * 1024 * 1024
MAX_EPUB_COMPRESSION_RATIO = 200

class ImportService:
    """Service to parse uploaded TXT and EPUB files, segment chapters,
    and add them directly into TruyenCloud library."""

    def detect_encoding(self, raw_bytes: bytes) -> str:
        """Heuristic detection of text encodings."""
        # Try UTF-8 first (most common)
        try:
            raw_bytes.decode("utf-8")
            return "utf-8"
        except UnicodeDecodeError:
            pass

        # Try GB18030/GBK (Chinese web novel files)
        try:
            raw_bytes.decode("gb18030")
            return "gb18030"
        except UnicodeDecodeError:
            pass

        # Try UTF-16
        try:
            raw_bytes.decode("utf-16")
            return "utf-16"
        except UnicodeDecodeError:
            pass

        # Fallback to windows-1258 / latin-1
        return "utf-8"

    def split_txt_chapters(self, text: str) -> List[Tuple[str, str]]:
        """Splits text content into list of (chapter_title, chapter_content) tuples."""
        lines = text.splitlines()
        chapters: List[Tuple[str, str]] = []
        current_title = "Mở đầu"
        current_body_lines: List[str] = []

        for line in lines:
            trimmed = line.strip()
            if not trimmed:
                current_body_lines.append("")
                continue

            # Check if line matches any chapter title regex
            is_chapter_title = False
            for pattern in CHAPTER_PATTERNS:
                m = pattern.match(trimmed)
                if m:
                    # Save previous chapter if has content
                    body = "\n".join(current_body_lines).strip()
                    if body or current_title != "Mở đầu":
                        chapters.append((current_title, body))
                    current_title = trimmed
                    current_body_lines = []
                    is_chapter_title = True
                    break

            if not is_chapter_title:
                current_body_lines.append(trimmed)

        # Save last chapter
        body = "\n".join(current_body_lines).strip()
        if body or current_title != "Mở đầu":
            chapters.append((current_title, body))

        # If no chapter titles found, split into ~2500-word pseudo chapters
        if len(chapters) <= 1 and len(text) > 5000:
            words = text.split()
            chunk_size = 2000
            chapters = []
            for i in range(0, len(words), chunk_size):
                ch_num = (i // chunk_size) + 1
                ch_text = " ".join(words[i:i + chunk_size])
                chapters.append((f"Chương {ch_num}", ch_text))

        return chapters

    def parse_epub(self, epub_bytes: bytes) -> Tuple[str, str, List[Tuple[str, str]]]:
        """Parses EPUB archive and extracts title, author, and chapter list."""
        import io
        title = "Tác phẩm nhập tệp"
        author = "Khuyết danh"
        chapters: List[Tuple[str, str]] = []

        with zipfile.ZipFile(io.BytesIO(epub_bytes)) as z:
            infos = z.infolist()
            if len(infos) > MAX_EPUB_ENTRIES:
                raise ValueError(f"EPUB có quá nhiều mục nén (tối đa {MAX_EPUB_ENTRIES:,}).")

            total_uncompressed = 0
            for info in infos:
                if info.is_dir():
                    continue
                total_uncompressed += info.file_size
                if total_uncompressed > MAX_EPUB_UNCOMPRESSED_BYTES:
                    raise ValueError(
                        "EPUB có tổng dung lượng sau giải nén vượt quá "
                        f"{MAX_EPUB_UNCOMPRESSED_BYTES // (1024 * 1024)} MB."
                    )
                compressed_size = max(info.compress_size, 1)
                if info.file_size / compressed_size > MAX_EPUB_COMPRESSION_RATIO:
                    raise ValueError("EPUB có tỷ lệ nén bất thường và bị từ chối.")

            # 1. Locate OPF file
            container_xml = z.read("META-INF/container.xml").decode("utf-8", errors="ignore")
            root_file_match = re.search(r'full-path="([^"]+)"', container_xml)
            if not root_file_match:
                raise ValueError("Tệp EPUB không hợp lệ (thiếu full-path trong container.xml)")

            opf_path = root_file_match.group(1)
            opf_dir = os.path.dirname(opf_path)
            opf_content = z.read(opf_path).decode("utf-8", errors="ignore")

            # Extract Title & Author
            title_m = re.search(r'<dc:title[^>]*>(.*?)</dc:title>', opf_content, re.IGNORECASE | re.DOTALL)
            if title_m:
                title = re.sub(r'<[^>]+>', '', title_m.group(1)).strip()

            creator_m = re.search(r'<dc:creator[^>]*>(.*?)</dc:creator>', opf_content, re.IGNORECASE | re.DOTALL)
            if creator_m:
                author = re.sub(r'<[^>]+>', '', creator_m.group(1)).strip()

            # Parse manifest items (attribute order-independent)
            manifest_items: Dict[str, str] = {}
            for tag_m in re.finditer(r'<item\b([^>]+)>', opf_content, re.IGNORECASE):
                attrs = tag_m.group(1)
                id_m = re.search(r'\bid\s*=\s*["\']([^"\']+)["\']', attrs, re.IGNORECASE)
                href_m = re.search(r'\bhref\s*=\s*["\']([^"\']+)["\']', attrs, re.IGNORECASE)
                if id_m and href_m:
                    manifest_items[id_m.group(1)] = href_m.group(1)

            # Parse spine order
            spine_ids = [
                m.group(1)
                for tag_m in re.finditer(r'<itemref\b([^>]+)>', opf_content, re.IGNORECASE)
                if (m := re.search(r'\bidref\s*=\s*["\']([^"\']+)["\']', tag_m.group(1), re.IGNORECASE))
            ]

            # Extract content in spine order
            extracted_chars = 0
            for idx, item_id in enumerate(spine_ids):
                href = manifest_items.get(item_id)
                if not href:
                    continue

                full_href = posixpath.normpath(posixpath.join(opf_dir, href))
                if full_href.startswith("../") or full_href.startswith("/"):
                    raise ValueError(f"Đường dẫn EPUB không hợp lệ: {href}")
                try:
                    info = z.getinfo(full_href)
                    if info.file_size > MAX_CHAPTER_CHARS * 4:
                        raise ValueError(f"Chương EPUB quá lớn: {full_href}")
                    html_content = z.read(full_href).decode("utf-8", errors="ignore")
                except KeyError:
                    continue

                # Extract title from <title> or <h1> or fallback
                ch_title = f"Chương {idx + 1}"
                h1_m = re.search(r'<h[1-3][^>]*>(.*?)</h[1-3]>', html_content, re.IGNORECASE | re.DOTALL)
                if h1_m:
                    ch_title = re.sub(r'<[^>]+>', '', h1_m.group(1)).strip()

                # Clean HTML tags to plain text
                # Replace <p> and <br> with newlines
                clean_text = re.sub(r'</?(p|div|br|h[1-6])[^>]*>', '\n', html_content, flags=re.IGNORECASE)
                clean_text = re.sub(r'<[^>]+>', '', clean_text)
                # Decode basic html entities
                clean_text = clean_text.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&amp;", "&")
                clean_text = "\n".join(l.strip() for l in clean_text.splitlines() if l.strip())

                if len(clean_text) > 50:
                    if len(clean_text) > MAX_CHAPTER_CHARS:
                        raise ValueError(f"Chương EPUB vượt quá {MAX_CHAPTER_CHARS:,} ký tự.")
                    extracted_chars += len(clean_text)
                    if extracted_chars > MAX_IMPORTED_TEXT_CHARS:
                        raise ValueError(
                            f"Tổng nội dung EPUB vượt quá {MAX_IMPORTED_TEXT_CHARS:,} ký tự."
                        )
                    chapters.append((ch_title, clean_text))

        return title, author, chapters

    async def import_novel(
        self,
        file_bytes: bytes,
        filename: str,
        custom_title: Optional[str] = None,
        custom_author: Optional[str] = None
    ) -> Dict[str, Any]:
        """Imports a TXT or EPUB file into TruyenCloud storage and database."""
        ext = os.path.splitext(filename)[1].lower()
        novel_id = "import_" + str(uuid.uuid4())[:8]

        title = custom_title or os.path.splitext(filename)[0]
        author = custom_author or "Độc giả nạp"
        chapters: List[Tuple[str, str]] = []

        if ext == ".epub":
            epub_title, epub_author, ch_list = self.parse_epub(file_bytes)
            if not custom_title and epub_title:
                title = epub_title
            if not custom_author and epub_author:
                author = epub_author
            chapters = ch_list
        else:
            # Default TXT
            encoding = self.detect_encoding(file_bytes)
            text = file_bytes.decode(encoding, errors="replace")
            chapters = self.split_txt_chapters(text)

        if not chapters:
            raise ValueError("Không thể trích xuất nội dung chương nào từ tệp đã chọn.")
        self._validate_chapters(chapters)

        logger.info(f"Đã phân tích tệp '{filename}' thành công: {len(chapters)} chương.")

        # Vietnamese translation for titles if Chinese detected
        title_vi = title
        try:
            translated_title = await translation_worker.translate_novel_title(title)
            if translated_title and translated_title != title:
                title_vi = translated_title
        except Exception:
            pass

        # Tạo ảnh bìa và dịch tựa các chương SONG SONG thay vì tuần tự
        chapter_titles = [c[0] for c in chapters]
        cover_res, titles_res = await asyncio.gather(
            asyncio.wait_for(cover_service.find_cover_by_title(title_vi), timeout=8.0),
            translation_worker.translate_chapter_titles_batch(chapter_titles, novel_id=novel_id),
            return_exceptions=True,
        )
        cover_url = ""
        if isinstance(cover_res, str):
            cover_url = cover_res
        elif isinstance(cover_res, Exception):
            logger.warning(f"Lỗi tạo ảnh bìa cho truyện import: {cover_res}")
        translated_chapter_titles = titles_res if isinstance(titles_res, list) else []

        # Save novel info
        novel_info = {
            "id": novel_id,
            "title": title,
            "title_vi": title_vi,
            "author": author,
            "author_vi": author,
            "description": f"Tác phẩm được nạp từ tệp cá nhân ({filename}) với {len(chapters)} chương.",
            "cover_url": cover_url,
            "source_id": "local_import",
            "source_url": "",
            "total_chapters": len(chapters),
            "status": "Hoàn thành",
            "shelf_name": "reading",
            "is_favorite": True,
            "created_at": datetime.now(timezone.utc).isoformat()
        }
        storage_service.save_novel_info(novel_id, novel_info)
        db.table("novels").upsert(novel_info).execute()

        # Save chapter files and index in bulk (m\u1ed9t l\u01b0\u1ee3t ghi thay v\u00ec t\u1eebng ch\u01b0\u01a1ng)
        ch_records = []
        chapters_index = []
        for idx, (ch_title, ch_body) in enumerate(chapters):
            ch_num = idx + 1
            ch_title_vi = translated_chapter_titles[idx] if idx < len(translated_chapter_titles) else ch_title

            ch_records.append({
                "novel_id": novel_id,
                "chapter_number": ch_num,
                "title": ch_title,
                "title_vi": ch_title_vi,
                "url": "",
                "original_content": ch_body,
                "translated_content": ch_body if not has_cjk(ch_body[:200]) else None,
                "translation_engine": "MANUAL_IMPORT"
            })
            chapters_index.append({
                "chapter_number": ch_num,
                "id": str(ch_num),
                "title": ch_title,
                "title_vi": ch_title_vi,
                "url": ""
            })

        from services.chapter_persistence import persist_chapters
        persist_chapters(novel_id, ch_records, chapters_index)

        logger.info(f"Đã nạp hoàn tất truyện '{title_vi}' ({novel_id}) với {len(chapters)} chương vào thư viện!")
        return {
            "status": "success",
            "novel_id": novel_id,
            "title": title_vi,
            "author": author,
            "total_chapters": len(chapters),
            "cover_url": cover_url
        }

    def _validate_chapters(self, chapters: List[Tuple[str, str]]) -> None:
        if len(chapters) > MAX_IMPORTED_CHAPTERS:
            raise ValueError(f"Tệp có quá nhiều chương (tối đa {MAX_IMPORTED_CHAPTERS:,}).")
        total_chars = 0
        for title, body in chapters:
            if len(body) > MAX_CHAPTER_CHARS:
                raise ValueError(f"Chương '{title[:80]}' vượt quá {MAX_CHAPTER_CHARS:,} ký tự.")
            total_chars += len(body)
            if total_chars > MAX_IMPORTED_TEXT_CHARS:
                raise ValueError(
                    f"Tổng nội dung sau giải nén vượt quá {MAX_IMPORTED_TEXT_CHARS:,} ký tự."
                )

import_service = ImportService()