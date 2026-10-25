import logging
import asyncio
import re
from typing import Optional, List
from services.agy_runner import TaskPriority

logger = logging.getLogger("truyencloud.english_adaptation")

class EnglishAdaptationMixin:
    """Adapts chapters to graded CEFR English levels while maintaining chunk alignment."""
    async def adapt_to_english_chapter(self, title: str, paragraphs: list[str], level: Optional[str] = "c1") -> dict:
        """Chuyen the TOAN BO chuong sang tieng Anh theo cap do CEFR, chia lo de khong bo sot doan.

        Truoc day ham nay cat cung `paragraphs_clean[:35]` khien chuong dai bi mat
        noi dung ma khong canh bao. Nay chia thanh nhieu lo, dich song song co gioi han
        (semaphore trong _run_agy) roi ghep lai theo dung thu tu.
        """
        paragraphs_clean = [p.strip() for p in paragraphs if p.strip()]
        if not paragraphs_clean:
            return {"title_en": title, "paragraphs_en": [], "paragraphs_vi": [], "is_fallback": False}

        agy = self._get_agy_path()
        lvl = (level or "c1").lower().strip()
        if lvl not in ("b1", "b2", "c1", "c2"):
            lvl = "c1"
        lvl_desc = {
            "b1": "tieng Anh ro rang, de hieu cap do CEFR B1 (tu vung Oxford 3000, cau van mach lac)",
            "b2": "tieng Anh tu nhien, hien dai cap do CEFR B2 (chuan tieu thuyet bestseller, loi cuon)",
            "c1": "tieng Anh van hoc cao cap cap do CEFR C1 (tu ngu phong phu, tu nhien, nhip dieu cuon hut)",
            "c2": "tieng Anh van hoc uyen bac Oxford cap do CEFR C2 (ngon tu co dien, thi vi, giau chieu sau triet ly)",
        }[lvl]

        if not agy:
            return {
                "title_en": title,
                "paragraphs_en": paragraphs_clean,
                "paragraphs_vi": paragraphs_clean,
                "is_fallback": True,
            }

        chunks = [
            paragraphs_clean[k:k + self.ADAPT_CHUNK_SIZE]
            for k in range(0, len(paragraphs_clean), self.ADAPT_CHUNK_SIZE)
        ]

        async def adapt_one(chunk_index: int, chunk: List[str]) -> Optional[dict]:
            content_text = "\n\n".join(chunk)
            prompt = (
                "Ban la mot dich gia van hoc Anh - My xuat chung.\n"
                f"Hay chuyen the phan chuong truyen sau sang {lvl_desc}:\n"
                f"Tieu de: {title}\n"
                f"So doan can tra ve: DUNG {len(chunk)} doan, khong gop, khong bot.\n"
                f"Noi dung:\n{content_text}\n\n"
                "Tra ve ket qua chuan theo JSON sau:\n"
                "{\n"
                '  "title_en": "English Title",\n'
                '  "paragraphs_en": ["Paragraph 1 in English", "Paragraph 2 in English"],\n'
                '  "paragraphs_vi": ["Doan 1 doi ung tieng Viet", "Doan 2 doi ung tieng Viet"]\n'
                "}\n"
                f"QUY TAC BAT BUOC: paragraphs_en PHAI 100% LA TIENG ANH (CEFR {lvl.upper()}), "
                f"va PHAI co dung {len(chunk)} phan tu tuong ung 1-1 voi cac doan dau vao. "
                "Chi tra ve chuoi JSON thuan tuy."
            )
            parsed = await self._run_agy_json(prompt, timeout=90, priority=TaskPriority.READER)
            if (
                parsed
                and isinstance(parsed.get("paragraphs_en"), list)
                and parsed["paragraphs_en"]
                and re.search(r"[a-zA-Z]{3,}", str(parsed["paragraphs_en"][0]))
            ):
                return parsed
            logger.warning(
                "Lo %d/%d chuyen the tieng Anh that bai, se dung ban goc cho lo nay.",
                chunk_index + 1, len(chunks)
            )
            return None

        results = await asyncio.gather(
            *(adapt_one(idx, chunk) for idx, chunk in enumerate(chunks)),
            return_exceptions=True,
        )

        paragraphs_en: List[str] = []
        paragraphs_vi: List[str] = []
        title_en = ""
        failed_chunks = 0

        for idx, (chunk, res) in enumerate(zip(chunks, results)):
            if isinstance(res, dict):
                en = [str(x) for x in res.get("paragraphs_en", []) if str(x).strip()]
                vi = [str(x) for x in res.get("paragraphs_vi", []) if str(x).strip()]
                if idx == 0 and res.get("title_en"):
                    title_en = str(res["title_en"])
                # Giu tuong ung 1-1: thieu thi bu bang ban goc, thua thi cat bot
                if len(en) < len(chunk):
                    en = en + chunk[len(en):]
                    failed_chunks += 1
                elif len(en) > len(chunk):
                    en = en[:len(chunk)]
                if len(vi) < len(chunk):
                    vi = vi + chunk[len(vi):]
                else:
                    vi = vi[:len(chunk)]
                paragraphs_en.extend(en)
                paragraphs_vi.extend(vi)
            else:
                if isinstance(res, BaseException):
                    logger.warning("Lo %d loi: %s", idx + 1, res)
                failed_chunks += 1
                paragraphs_en.extend(chunk)
                paragraphs_vi.extend(chunk)

        return {
            "title_en": title_en or title,
            "paragraphs_en": paragraphs_en,
            "paragraphs_vi": paragraphs_vi,
            "total_paragraphs": len(paragraphs_clean),
            "failed_chunks": failed_chunks,
            "is_fallback": failed_chunks == len(chunks),
        }

