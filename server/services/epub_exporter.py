"""Xuất truyện trong kho 1TB ra tệp sách điện tử `.epub` chuẩn (EPUB 2.0 / Kindle / Boox / Apple Books)."""

import hashlib
import html
import io
import json
import os
import re
import uuid
import zipfile
from typing import Any, Dict, List, Optional, Tuple

from services.storage_service import DATA_DIR, storage_service
from services.title_dict_text import strip_acc
from services.translation_validation import is_usable_translation


def _safe_filename(name: str) -> str:
    folded = strip_acc((name or "truyen").strip())
    clean = re.sub(r"[^a-zA-Z0-9\s\-]", "", folded)
    clean = re.sub(r"\s+", "-", clean).strip("-")
    return clean[:80] or "truyencloud"


def _strip_redundant_heading(raw_paras: List[str], ch_title: str, ch_num: int) -> List[str]:
    if not raw_paras:
        return raw_paras
    first = raw_paras[0].strip()
    title = (ch_title or "").strip()
    prefix = f"chương {ch_num}"
    if (
        (title and first.lower() == title.lower())
        or (title.lower().startswith(prefix) and first.lower().startswith(prefix))
    ):
        return raw_paras[1:]
    return raw_paras


def _load_cached_cover(cover_url: str) -> Optional[bytes]:
    clean_url = (cover_url or "").strip()
    if not clean_url:
        return None
    url_hash = hashlib.md5(clean_url.encode("utf-8")).hexdigest()
    candidate_dirs = [
        os.path.join(DATA_DIR, "covers_cache"),
        os.path.join(os.path.dirname(storage_service.base_dir), "covers_cache"),
    ]
    for cdir in candidate_dirs:
        for ext in (".jpg", ".img"):
            cpath = os.path.join(cdir, f"{url_hash}{ext}")
            if os.path.isfile(cpath):
                try:
                    with open(cpath, "rb") as f:
                        data = f.read()
                    if data:
                        return data
                except Exception:
                    continue
    return None


def _load_exportable_chapters(novel_id: str, translated_only: bool = True) -> List[Dict[str, Any]]:
    chapters_dir = storage_service.get_chapters_dir(novel_id)
    if not os.path.isdir(chapters_dir):
        return []
    items: List[Dict[str, Any]] = []
    for fname in os.listdir(chapters_dir):
        if not (fname.startswith("chapter_") and fname.endswith(".json")):
            continue
        fpath = os.path.join(chapters_dir, fname)
        try:
            with open(fpath, "r", encoding="utf-8") as f:
                data = json.load(f)
            if not isinstance(data, dict):
                continue
            ch_num = int(data.get("chapter_number") or 0)
            if ch_num <= 0:
                continue
            vi_text = (data.get("translated_content") or "").strip()
            zh_text = (data.get("original_content") or "").strip()
            if translated_only and not is_usable_translation(vi_text):
                continue
            body_text = vi_text if is_usable_translation(vi_text) else zh_text
            if not body_text:
                continue
            items.append({
                "chapter_number": ch_num,
                "title": (data.get("title_vi") or data.get("title") or f"Chương {ch_num}").strip(),
                "content": body_text,
            })
        except Exception:
            continue
    items.sort(key=lambda x: x["chapter_number"])
    return items


def build_novel_epub(novel_id: str, translated_only: bool = True) -> Optional[Tuple[bytes, str]]:
    """Đóng gói các chương của `novel_id` thành tệp `.epub` trong bộ nhớ.

    Trả về `(epub_bytes, filename)` hoặc `None` nếu chưa có chương nào đủ điều kiện.
    """
    info = storage_service.get_novel_info(novel_id) or {}
    chapters = _load_exportable_chapters(novel_id, translated_only=translated_only)
    if not chapters and translated_only:
        chapters = _load_exportable_chapters(novel_id, translated_only=False)
    if not chapters:
        return None

    title = (info.get("title_vi") or info.get("title") or novel_id).strip()
    author = (info.get("author") or "TruyenCloud").strip()
    description = (info.get("description") or "").strip()
    book_uuid = str(uuid.uuid5(uuid.NAMESPACE_DNS, f"truyencloud.novel.{novel_id}"))

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        # 1. mimetype phải nằm đầu tiên và không nén
        zf.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)

        # 2. META-INF/container.xml
        zf.writestr(
            "META-INF/container.xml",
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">\n'
            '  <rootfiles>\n'
            '    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>\n'
            "  </rootfiles>\n"
            "</container>",
        )

        # 3. OEBPS/style.css
        zf.writestr(
            "OEBPS/style.css",
            "body { font-family: serif; line-height: 1.65; margin: 4%; }\n"
            "h1, h2 { font-family: sans-serif; line-height: 1.3; margin-bottom: 1em; }\n"
            "p { text-indent: 1.2em; margin: 0.55em 0; text-align: justify; }\n",
        )

        manifest_items = [
            '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>',
            '<item id="css" href="style.css" media-type="text/css"/>',
        ]
        cover_meta = ""
        cover_bytes = _load_cached_cover(info.get("cover_url") or "")
        if cover_bytes:
            zf.writestr("OEBPS/cover.jpg", cover_bytes)
            manifest_items.append(
                '<item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>'
            )
            cover_meta = '    <meta name="cover" content="cover-image"/>\n'
        spine_items = []
        nav_points = []

        for idx, ch in enumerate(chapters, start=1):
            ch_id = f"ch_{idx}"
            href = f"chapter_{idx}.xhtml"
            ch_title_esc = html.escape(ch["title"])
            raw_paras = _strip_redundant_heading(
                [p.strip() for p in ch["content"].splitlines() if p.strip()],
                ch["title"],
                int(ch.get("chapter_number") or idx),
            )
            paras = [f"<p>{html.escape(p)}</p>" for p in raw_paras]
            xhtml = (
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" '
                '"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">\n'
                '<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="vi">\n'
                f"<head><title>{ch_title_esc}</title>"
                '<link rel="stylesheet" type="text/css" href="style.css"/></head>\n'
                f"<body><h2>{ch_title_esc}</h2>\n"
                + "\n".join(paras)
                + "\n</body></html>"
            )
            zf.writestr(f"OEBPS/{href}", xhtml)
            manifest_items.append(
                f'<item id="{ch_id}" href="{href}" media-type="application/xhtml+xml"/>'
            )
            spine_items.append(f'<itemref idref="{ch_id}"/>')
            nav_points.append(
                f'<navPoint id="navPoint-{idx}" playOrder="{idx}">'
                f"<navLabel><text>{ch_title_esc}</text></navLabel>"
                f'<content src="{href}"/></navPoint>'
            )

        # 4. OEBPS/content.opf
        opf = (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">\n'
            '  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">\n'
            f"    <dc:title>{html.escape(title)}</dc:title>\n"
            f"    <dc:creator>{html.escape(author)}</dc:creator>\n"
            "    <dc:language>vi</dc:language>\n"
            f'    <dc:identifier id="BookId">urn:uuid:{book_uuid}</dc:identifier>\n'
            f"    <dc:description>{html.escape(description)}</dc:description>\n"
            f"{cover_meta}"
            "  </metadata>\n"
            "  <manifest>\n    " + "\n    ".join(manifest_items) + "\n  </manifest>\n"
            '  <spine toc="ncx">\n    ' + "\n    ".join(spine_items) + "\n  </spine>\n"
            "</package>"
        )
        zf.writestr("OEBPS/content.opf", opf)

        # 5. OEBPS/toc.ncx
        ncx = (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">\n'
            "  <head>\n"
            f'    <meta name="dtb:uid" content="urn:uuid:{book_uuid}"/>\n'
            "  </head>\n"
            f"  <docTitle><text>{html.escape(title)}</text></docTitle>\n"
            "  <navMap>\n    " + "\n    ".join(nav_points) + "\n  </navMap>\n"
            "</ncx>"
        )
        zf.writestr("OEBPS/toc.ncx", ncx)

    filename = f"{_safe_filename(title)}-{len(chapters)}ch.epub"
    return buf.getvalue(), filename
