import logging
from typing import Optional, Dict

logger = logging.getLogger("truyencloud.reading_intel")

class ReadingIntelligenceMixin:
    """Lore term explanation and word/sentence analysis.

    Recap "Previously On" nằm ở services/chapter_recap_service.py (tóm tắt từng chương có cache).
    """
    async def explain_lore_term(
        self,
        term: str,
        context: Optional[str] = None
    ) -> Dict:
        """Giải nghĩa từ ngữ Hán Việt, điển tích, bối cảnh tu tiên hoặc danh từ riêng."""
        agy = self._get_agy_path()
        if not agy:
            return self._lore_fallback(term, "Antigravity CLI chưa sẵn sàng trên máy chủ.")

        prompt = (
            f"Bạn là chuyên gia ngôn ngữ học và từ nguyên Hán Việt cổ điển.\n"
            f"Hãy giải nghĩa thuật ngữ: \"{term}\"\n"
            f"Đoạn văn ngữ cảnh: \"{context or 'Không có'}\"\n\n"
            f"Trả về kết quả chuẩn theo JSON sau:\n"
            f"{{\n"
            f"  \"term\": \"{term}\",\n"
            f"  \"hanzi\": \"Chữ Hán tương ứng\",\n"
            f"  \"pinyin\": \"Pinyin có dấu thanh điệu\",\n"
            f"  \"sino_vietnamese\": \"Âm Hán Việt chuẩn\",\n"
            f"  \"definition\": \"Nghĩa từ điển và phân tích ngữ nghĩa súc tích (1-2 câu).\",\n"
            f"  \"lore_context\": \"Ý nghĩa trong văn học tu tiên / kiếm hiệp / bối cảnh truyện (nếu có).\"\n"
            f"}}\n"
            f"QUY TẮC: Chỉ trả về chuỗi JSON thuần túy hợp lệ, không bọc trong markdown hay thêm bình luận."
        )

        parsed = await self._run_agy_json(prompt, timeout=25)
        if parsed and ("definition" in parsed):
            return {**parsed, "is_fallback": False, "error": None}
        logger.warning("Lỗi giải nghĩa Lore qua Antigravity: Antigravity khong tra JSON hop le, dung du lieu du phong.")
        return self._lore_fallback(term, "Không giải nghĩa được thuật ngữ này. Vui lòng thử lại.")

    @staticmethod
    def _lore_fallback(term: str, error: str) -> Dict:
        # Client PHAI hien trang thai loi thay vi hien dinh nghia bia; khong duoc cache
        return {
            "term": term,
            "hanzi": "",
            "pinyin": "",
            "sino_vietnamese": "",
            "definition": "",
            "lore_context": "",
            "is_fallback": True,
            "error": error,
        }

    async def explain_english_word(
        self,
        word: str,
        context: Optional[str] = None,
        book_title: Optional[str] = None
    ) -> dict:
        """Phân tích chuyên sâu từ vựng tiếng Anh theo chuẩn Apple Dictionary & CEFR."""
        clean_word = word.strip().strip(".,!?;:\"'()[]{}“”‘’")
        agy = self._get_agy_path()
        if not agy:
            return {
                "word": clean_word,
                "ipa_us": "",
                "ipa_uk": "",
                "part_of_speech": "",
                "cefr_level": "",
                "context_meaning_vi": "",
                "english_definition": "",
                "etymology": "",
                "collocations": [],
                "example_sentences": [],
                # Client PHAI hien trang thai loi thay vi hien du lieu bia
                "is_fallback": True,
                "error": "Antigravity CLI chua san sang tren may chu."
            }

        prompt = (
            f"Bạn là giáo sư ngôn ngữ học và chuyên gia từ vựng học thuật Oxford/Cambridge.\n"
            f"Hãy phân tích chuyên sâu từ tiếng Anh sau dành cho một học giả/nghiên cứu sinh đang đọc sách:\n"
            f"Từ: \"{clean_word}\"\n"
            f"Ngữ cảnh trong câu: \"{context or 'Không có'}\"\n"
            f"Tác phẩm: \"{book_title or 'Chưa rõ'}\"\n\n"
            f"Hãy trả về kết quả chuẩn theo định dạng JSON sau:\n"
            f"{{\n"
            f"  \"word\": \"{clean_word}\",\n"
            f"  \"ipa_us\": \"/phiên âm Mỹ/\",\n"
            f"  \"ipa_uk\": \"/phiên âm Anh/\",\n"
            f"  \"part_of_speech\": \"noun/verb/adjective/idiom/phrasal verb\",\n"
            f"  \"cefr_level\": \"B2 / C1 / C2 / Academic\",\n"
            f"  \"context_meaning_vi\": \"Nghĩa tiếng Việt tinh tế, chuẩn xác nhất trong ngữ cảnh câu văn này\",\n"
            f"  \"english_definition\": \"Định nghĩa súc tích chuẩn từ điển Oxford bằng tiếng Anh\",\n"
            f"  \"etymology\": \"Nguồn gốc từ nguyên (gốc Latin, Hy Lạp, Pháp cổ... giúp nhớ sâu bản chất từ)\",\n"
            f"  \"collocations\": [\"cụm từ hay dùng 1\", \"cụm từ hay dùng 2\"],\n"
            f"  \"example_sentences\": [\n"
            f"    \"Câu ví dụ thực tế 1\",\n"
            f"    \"Câu ví dụ thực tế 2\"\n"
            f"  ]\n"
            f"}}\n"
            f"QUY TẮC: Chỉ trả về chuỗi JSON thuần túy hợp lệ, không bọc trong markdown hay thêm bình luận."
        )

        parsed = await self._run_agy_json(prompt, timeout=30)
        if parsed and (("context_meaning_vi" in parsed or "cefr_level" in parsed)):
            return parsed
        logger.warning("Antigravity khong tra JSON hop le khi phan tich tu %s - dung du lieu du phong.", clean_word)

        return {
            "word": clean_word,
            "ipa_us": "",
            "ipa_uk": "",
            "part_of_speech": "",
            "cefr_level": "",
            "context_meaning_vi": "",
            "english_definition": "",
            "etymology": "",
            "collocations": [],
            "example_sentences": [],
            "is_fallback": True,
            "error": "Khong phan tich duoc tu nay. Vui long thu lai."
        }

    async def translate_english_sentence(self, sentence: str) -> dict:
        """Dịch phân tích câu tiếng Anh kèm cấu trúc ngữ pháp và điểm ngữ pháp quan trọng."""
        clean_sentence = sentence.strip()
        agy = self._get_agy_path()
        if not agy:
            return {
                "english_sentence": clean_sentence,
                "vietnamese_translation": "",
                "grammar_points": [],
                "key_vocabulary": [],
                "is_fallback": True,
                "error": "Antigravity CLI chua san sang tren may chu."
            }

        prompt = (
            f"Bạn là chuyên gia sư phạm dịch thuật văn học Anh - Việt cao cấp.\n"
            f"Hãy phân tích và dịch câu văn tiếng Anh sau:\n"
            f"\"{clean_sentence}\"\n\n"
            f"Trả về kết quả chuẩn theo JSON sau:\n"
            f"{{\n"
            f"  \"english_sentence\": \"{clean_sentence}\",\n"
            f"  \"vietnamese_translation\": \"Bản dịch tiếng Việt văn phong tự nhiên, tao nhã\",\n"
            f"  \"grammar_points\": [\n"
            f"    {{\"structure\": \"Tên cấu trúc/mẫu câu\", \"explanation\": \"Cách dùng và lưu ý\"}}\n"
            f"  ],\n"
            f"  \"key_vocabulary\": [\n"
            f"    {{\"word\": \"từ vựng cốt lõi\", \"meaning\": \"nghĩa\"}}\n"
            f"  ]\n"
            f"}}\n"
            f"QUY TẮC: Chỉ trả về chuỗi JSON thuần túy hợp lệ, không bọc trong markdown hay thêm bình luận."
        )

        parsed = await self._run_agy_json(prompt, timeout=30)
        if parsed and ("vietnamese_translation" in parsed):
            return parsed
        logger.warning("Antigravity khong tra JSON hop le khi phan tich cau - dung du lieu du phong.")

        return {
            "english_sentence": clean_sentence,
            "vietnamese_translation": "",
            "grammar_points": [],
            "key_vocabulary": [],
            "is_fallback": True,
            "error": "Khong phan tich duoc cau nay. Vui long thu lai."
        }

    # So doan toi da moi lo khi chuyen the chuong sang tieng Anh
    ADAPT_CHUNK_SIZE = 18

