import logging
import re
from typing import List, Optional
from services.agy_runner import TaskPriority
from services.hanviet_dict import has_cjk

logger = logging.getLogger("truyencloud.title_translation")

class TitleTranslationMixin:
    """Novel title, author name, and batch chapter title translation."""
    async def translate_novel_title(self, title: str) -> str:
        """Tựa truyện Trung -> Việt tức thì, không Google: tuyển chọn > bản Hy-MT2 đã tinh chỉnh > từ điển convert.

        Tựa hiện đại (dịch nghĩa) được xếp hàng cho Hy-MT2 ở nền; Antigravity chỉ còn là đường cuối khi kết quả
        vẫn còn chữ Hán (từ điển chưa sẵn sàng và bảng Hán-Việt nhỏ thiếu chữ)."""
        if not title or not title.strip():
            return ""
        from services.title_dict_text import clean_book_source
        clean_title = clean_book_source(title)
        if not has_cjk(clean_title):
            return clean_title
        if clean_title in self._title_cache:
            return self._title_cache[clean_title]

        from services.title_service import book_title
        vi, src = book_title(clean_title)
        if vi and not has_cjk(vi):
            # Bản từ điển của tựa đang chờ mô hình không được ghim trong bộ nhớ: lần sau phải thấy bản tinh chỉnh.
            if src in ("curated", "llm", "names", "vietphrase"):
                self._title_cache[clean_title] = vi
            return vi

        agy = self._get_agy_path()
        if agy:
            prompt = (
                f"Dịch tựa đề tiểu thuyết tiếng Trung sau sang tên tiếng Việt hay, chuẩn văn học nhất. "
                f"Chỉ trả về DUY NHẤT tên tiếng Việt, không kèm bất kỳ từ nào khác: {clean_title}"
            )
            try:
                raw_out = await self._run_agy(
                    prompt,
                    timeout=20,
                    priority=TaskPriority.INTERACTIVE,
                    task_key=f"title:{clean_title}"
                )
                if raw_out:
                    res = raw_out.replace('"', '').replace("'", "")
                    res = re.sub(r"^(Tên truyện[:\s]*|Tựa đề[:\s]*|Dịch[:\s]*)", "", res, flags=re.IGNORECASE).strip()
                    if res and len(res) < 100 and not has_cjk(res):
                        self._title_cache[clean_title] = res
                        return res
            except Exception as e:
                logger.warning(f"Lỗi dịch tựa đề truyện qua Antigravity: {e}")

        return vi or clean_title

    async def translate_author_name(self, author: str) -> str:
        """Translates an author name from Chinese to Sino-Vietnamese / Vietnamese."""
        if not author or not author.strip():
            return "Khuyết Danh"

        clean_author = author.strip()
        clean_author = re.sub(r"^(作者[:：\s]*|Tác giả[:：\s]*)", "", clean_author).strip()

        # If already Vietnamese/Latin (no CJK characters)
        if not has_cjk(clean_author):
            return clean_author

        if clean_author in self._author_cache:
            return self._author_cache[clean_author]

        # Author names in Vietnamese web novel reading are predominantly Sino-Vietnamese
        from services.hanviet_dict import transliterate_to_hanviet
        hv_name = transliterate_to_hanviet(clean_author)
        if hv_name and not has_cjk(hv_name):
            self._author_cache[clean_author] = hv_name
            return hv_name

        agy = self._get_agy_path()
        if agy:
            prompt = (
                f"Dịch tên tác giả tiểu thuyết tiếng Trung sau sang âm Hán-Việt chuẩn xác nhất. "
                f"Chỉ trả về DUY NHẤT tên tiếng Việt, không kèm bất kỳ từ nào khác: {clean_author}"
            )
            try:
                raw_out = await self._run_agy(
                    prompt,
                    timeout=15,
                    priority=TaskPriority.INTERACTIVE,
                    task_key=f"author:{clean_author}"
                )
                if raw_out:
                    res = raw_out.replace('"', '').replace("'", "")
                    res = re.sub(r"^(Tên tác giả[:\s]*|Tác giả[:\s]*|Dịch[:\s]*)", "", res, flags=re.IGNORECASE).strip()
                    if res and len(res) < 50 and not has_cjk(res):
                        self._author_cache[clean_author] = res
                        return res
            except Exception as e:
                logger.warning(f"Lỗi dịch tên tác giả qua Antigravity: {e}")

        return clean_author

    async def translate_chapter_titles_batch(self, titles: List[str], novel_id: Optional[str] = None) -> List[str]:
        """Tựa chương Trung -> 'Chương X: [Tên]' tức thì bằng từ điển convert (cache Hy-MT2 trước); tựa dễ sai trật tự
        từ được xếp hàng cho Hy-MT2 ở nền, novel_id cho phép vá chapters_index.json khi bản tinh chỉnh về."""
        if not titles:
            return []

        try:
            from services.fast_translator import fast_translator
            fast_res = await fast_translator.translate_batch_chapter_titles(titles, novel_id=novel_id)
            if fast_res and len(fast_res) == len(titles):
                return fast_res
        except Exception as e:
            logger.warning(f"FastTranslator batch chapter titles failed: {e}")

        # Guard cuối: không bao giờ để lỗi bất ngờ lan lên caller
        from services.title_extractor import parse_chinese_chapter, format_chapter_title
        return [
            format_chapter_title(parse_chinese_chapter(t, i + 1)[0], t)
            for i, t in enumerate(titles)
        ]

    async def translate_novel_description(self, description: str) -> str:
        """Translates a novel synopsis/description from Chinese to Vietnamese."""
        if not description or not description.strip():
            return ""
        clean_desc = description.strip()
        # If already Vietnamese/Latin
        if not has_cjk(clean_desc):
            return clean_desc

        # 1. Văn bản dài: dịch theo đoạn qua module dự phòng chương (ngoài phạm vi thay Google cho văn bản ngắn)
        try:
            from services.fast_translator import fast_translator
            gt_desc = await fast_translator.translate_long_text(clean_desc[:800], tl="vi")
            if gt_desc and len(gt_desc) > 10 and not has_cjk(gt_desc):
                return gt_desc
        except Exception as e:
            logger.debug(f"FastTranslator novel description failed: {e}")

        from services.hanviet_dict import transliterate_to_hanviet
        agy = self._get_agy_path()
        if agy:
            prompt = (
                f"Hãy dịch phần tóm tắt tiểu thuyết sau sang tiếng Việt một cách tự nhiên, cuốn hút. "
                f"Chỉ trả về nội dung tóm tắt bằng tiếng Việt, không thêm lời chào hay giải thích:\n\n"
                f"{clean_desc[:800]}"
            )
            try:
                raw_out = await self._run_agy(
                    prompt,
                    timeout=5.0,
                    priority=TaskPriority.INTERACTIVE,
                    task_key=f"desc:{hash(clean_desc[:100])}"
                )
                if raw_out and len(raw_out) > 10:
                    return raw_out.strip()
            except Exception as e:
                logger.warning(f"Lỗi dịch tóm tắt truyện qua Antigravity: {e}")

        # Fallback to fast Hán-Việt transliteration
        return transliterate_to_hanviet(clean_desc)
