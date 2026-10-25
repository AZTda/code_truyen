import logging
from typing import Optional, List, Dict, Any

logger = logging.getLogger("truyencloud.mnemonic_story")

class MnemonicStoryMixin:
    """Visual mnemonic prompts and context story weaving."""
    async def generate_visual_mnemonic(
        self,
        word: str,
        meaning: Optional[str] = "",
        context: Optional[str] = ""
    ) -> Dict[str, Any]:
        """Prompts Gemini via Antigravity CLI to generate visual mnemonic concept & palette."""
        agy = self._get_agy_path()
        clean_word = word.strip().lower()

        # Quick curated defaults
        defaults = {
            "ephemeral": {
                "mnemonic": "Nhớ đến cánh hoa bồ công anh tan trong gió hoàng hôn — rực rỡ nhưng mong manh, chỉ tồn tại trong chớp mắt rồi biến tan vào hư vô.",
                "symbol_type": "flower",
                "palette_hex": "#D488FF",
                "art_title": "The Whispering Petal",
                "collocations": [
                    {"phrase": "ephemeral beauty", "meaning": "vẻ đẹp mong manh, sớm nở tối tàn"},
                    {"phrase": "ephemeral trend", "meaning": "trào lưu nhất thời, chóng tàn"},
                    {"phrase": "ephemeral pleasure", "meaning": "thú vui phù du, ngắn ngủi"}
                ],
                "synonyms": ["fleeting", "transient", "momentary", "evanescent"],
                "antonyms": ["permanent", "enduring", "eternal"],
                "english_definition": "Lasting for a very short time; fleeting or transitory.",
                "context_translation_vi": "Tuổi trẻ và những đóa hoa mùa thu phù du đều mang một vẻ đẹp mong manh, ngắn ngủi."
            },
            "serendipity": {
                "mnemonic": "Chiếc la bàn cổ phát sáng dưới kẽ sách cũ — biểu tượng của những may mắn diệu kỳ tình cờ tìm thấy khi tâm trí không hề toan tính.",
                "symbol_type": "compass",
                "palette_hex": "#4EDECA",
                "art_title": "Celestial Compass",
                "collocations": [
                    {"phrase": "pure serendipity", "meaning": "hoàn toàn do cơ duyên may mắn"},
                    {"phrase": "fortunate serendipity", "meaning": "sự tình cờ may mắn kỳ diệu"},
                    {"phrase": "by pure serendipity", "meaning": "một cách tình cờ ngẫu nhiên"}
                ],
                "synonyms": ["fluke", "coincidence", "happy accident"],
                "antonyms": ["misfortune", "bad luck"],
                "english_definition": "The occurrence of events by chance in a happy or beneficial way.",
                "context_translation_vi": "Cuộc gặp gỡ giữa tôi và người cộng sự kinh doanh hoàn toàn là một cơ duyên may mắn kỳ diệu."
            }
        }
        if clean_word in defaults:
            return defaults[clean_word]

        if not agy:
            return {
                "is_fallback": True,
                "error": "AI chưa tạo được thẻ ghi nhớ cho từ này. Vui lòng thử lại sau.",
                "mnemonic": f"Hình tượng ẩn dụ sâu sắc cho từ '{clean_word}' ({meaning}).",
                "symbol_type": "crystal",
                "palette_hex": "#8CB4FF",
                "art_title": "Luminous Arcana",
                "collocations": [
                    {"phrase": f"apply {clean_word}", "meaning": f"áp dụng {clean_word}"}
                ],
                "synonyms": [],
                "antonyms": [],
                "english_definition": meaning or "",
                "context_translation_vi": ""
            }

        prompt = (
            f"Bạn là chuyên gia ngôn ngữ học & thiết kế thẻ Flashcard thông minh (Quizlet, MochiMochi, Anki).\n"
            f"Hãy phân tích chuyên sâu từ tiếng Anh sau để học viên ghi nhớ sâu:\n"
            f"- Từ: {clean_word}\n"
            f"- Nghĩa: {meaning}\n"
            f"- Ngữ cảnh: {context}\n\n"
            f"Yêu cầu xuất ra JSON duy nhất:\n"
            f"{{\n"
            f"  \"mnemonic\": \"Mẹo nhớ hình ảnh MochiMochi tiếng Việt (1-2 câu giàu liên tưởng thị giác sâu sắc)\",\n"
            f"  \"symbol_type\": \"flower\" hoặc \"compass\" hoặc \"crystal\" hoặc \"flame\",\n"
            f"  \"palette_hex\": \"#RRGGBB\",\n"
            f"  \"art_title\": \"Tiêu đề hình tượng nghệ thuật\",\n"
            f"  \"collocations\": [\n"
            f"    {{\"phrase\": \"cụm từ 1\", \"meaning\": \"nghĩa tiếng Việt\"}},\n"
            f"    {{\"phrase\": \"cụm từ 2\", \"meaning\": \"nghĩa tiếng Việt\"}}\n"
            f"  ],\n"
            f"  \"synonyms\": [\"từ đồng nghĩa 1\", \"từ đồng nghĩa 2\"],\n"
            f"  \"antonyms\": [\"từ trái nghĩa 1\", \"từ trái nghĩa 2\"],\n"
            f"  \"english_definition\": \"Định nghĩa Oxford/Cambridge ngắn gọn\",\n"
            f"  \"context_translation_vi\": \"Dịch câu ngữ cảnh sang tiếng Việt tự nhiên\"\n"
            f"}}\n"
            f"Chỉ trả về duy nhất JSON."
        )

        parsed = await self._run_agy_json(prompt, timeout=30)
        if parsed and ("mnemonic" in parsed):
            return parsed
        logger.warning("Antigravity khong tra JSON hop le khi tao visual mnemonic cho %s.", clean_word)

        return {
            "is_fallback": True,
            "error": "AI chưa tạo được thẻ ghi nhớ cho từ này. Vui lòng thử lại sau.",
            "mnemonic": f"Biểu tượng thị giác và mỏ neo ký ức cho từ '{clean_word}'.",
            "symbol_type": "crystal",
            "palette_hex": "#8CB4FF",
            "art_title": "Luminous Arcana",
            "collocations": [],
            "synonyms": [],
            "antonyms": [],
            "english_definition": meaning or "",
            "context_translation_vi": ""
        }

    async def weave_context_story(
        self,
        words: List[str],
        book_title: Optional[str] = "",
        domain: Optional[str] = ""
    ) -> Dict[str, Any]:
        """Dệt vi truyện song ngữ kết nối các từ vựng theo cụm ngữ cảnh cuốn tiểu thuyết hoặc chủ đề học."""
        clean_words = [w.strip().lower() for w in words if w.strip()]
        if not clean_words:
            return {
                "english_story": "No context words provided to weave story.",
                "vietnamese_translation": "Chưa có từ vựng ngữ cảnh nào để dệt truyện.",
                "highlights": []
            }

        # Fast curated path for ephemeral & serendipity in Boston
        words_set = set(clean_words)
        if "ephemeral" in words_set and "serendipity" in words_set:
            return {
                "english_story": "During an ephemeral autumn afternoon in Boston, Clara wandered into a quaint cobblestone bookstore by pure serendipity. Though the golden foliage would vanish within days, the unexpected encounter altered the course of her life forever.",
                "vietnamese_translation": "Vào một buổi chiều thu Boston ngắn ngủi, Clara tình cờ bước vào một hiệu sách lát đá cuội cổ kính hoàn toàn do cơ duyên may mắn. Dẫu sắc vàng của lá cây sẽ biến tan chỉ trong vài ngày, cuộc tao ngộ bất ngờ ấy đã thay đổi hoàn toàn quỹ đạo cuộc đời cô mãi mãi.",
                "highlights": [
                    {
                        "word": "ephemeral",
                        "collocation": "ephemeral autumn afternoon",
                        "explanation": "Khoảnh khắc chiều thu ngắn ngủi, trôi qua nhanh chóng."
                    },
                    {
                        "word": "serendipity",
                        "collocation": "by pure serendipity",
                        "explanation": "Hoàn toàn do cơ duyên tình cờ may mắn."
                    }
                ]
            }

        agy = self._get_agy_path()
        novel_ctx = f"trong bối cảnh tiểu thuyết '{book_title}'" if book_title else ""
        domain_ctx = f"thuộc miền chủ đề '{domain}'" if domain else ""
        words_str = ", ".join(clean_words[:5])

        if not agy:
            joined = " and ".join(clean_words)
            return {
                "english_story": f"In a thoughtful moment {novel_ctx}, she reflected on how {joined} shaped the narrative of their journey together.",
                "vietnamese_translation": f"Trong một khoảnh khắc trầm lắng {novel_ctx}, cô suy ngẫm về việc {joined} đã định hình câu chuyện hành trình của họ như thế nào.",
                "highlights": [{"word": w, "collocation": w, "explanation": "Từ vựng trọng tâm trong ngữ cảnh"} for w in clean_words]
            }

        prompt = (
            f"Bạn là nhà văn và chuyên gia ngôn ngữ học AI cao cấp.\n"
            f"Nhiệm vụ: Hãy dệt một vi truyện (micro-story) tinh tế dài đúng 2-3 câu bằng tiếng Anh {novel_ctx} {domain_ctx}, "
            f"kết nối tự nhiên và làm nổi bật các từ vựng mục tiêu sau: {words_str}.\n\n"
            f"Yêu cầu đầu ra duy nhất là JSON theo định dạng:\n"
            f"{{\n"
            f"  \"english_story\": \"Đoạn vi truyện tiếng Anh súc tích, văn phong tiểu thuyết sâu lắng, tự nhiên.\",\n"
            f"  \"vietnamese_translation\": \"Bản dịch tiếng Việt văn chương, truyền cảm.\",\n"
            f"  \"highlights\": [\n"
            f"    {{\n"
            f"      \"word\": \"từ\",\n"
            f"      \"collocation\": \"cụm từ chứa từ đó trong truyện\",\n"
            f"      \"explanation\": \"giải thích ngắn gọn ý nghĩa trong ngữ cảnh câu chuyện\"\n"
            f"    }}\n"
            f"  ]\n"
            f"}}\n"
            f"Chỉ trả về JSON thuần túy, không thêm bất kỳ văn bản giải thích nào khác."
        )

        parsed = await self._run_agy_json(prompt, timeout=30)
        if parsed and ("english_story" in parsed):
            return parsed
        logger.warning("Antigravity khong tra JSON hop le khi det truyen ngu canh.")

        joined = " and ".join(clean_words)
        return {
            "english_story": f"In a thoughtful moment {novel_ctx}, she reflected on how {joined} shaped the narrative of their journey together.",
            "vietnamese_translation": f"Trong một khoảnh khắc trầm lắng {novel_ctx}, cô suy ngẫm về việc {joined} đã định hình câu chuyện hành trình của họ như thế nào.",
            "highlights": [{"word": w, "collocation": w, "explanation": "Từ vựng trọng tâm"} for w in clean_words]
        }
