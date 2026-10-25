import hashlib
import logging
from typing import Optional, List
from services.agy_runner import TaskPriority

logger = logging.getLogger("truyencloud.vocabulary_ai")

_ERR_NO_CLI = "Antigravity CLI chưa sẵn sàng trên máy chủ."
_ERR_NO_SCENARIOS = "AI chưa tạo được tình huống cho từ này. Vui lòng thử lại sau."

class VocabularyAiMixin:
    """Real-life conversation scenarios, sentence grading, and scenario quizzes."""
    async def generate_life_scenarios(
        self,
        word: str,
        context_meaning_vi: Optional[str] = None,
        part_of_speech: Optional[str] = None,
        cefr_level: Optional[str] = "B2"
    ) -> dict:
        """Sinh các tình huống ứng dụng đời thực (Công sở/Email, Hội thoại hàng ngày, Mẹo bản xứ) cho từ vựng."""
        clean_word = word.strip().strip(".,!?;:\"'()[]{}“”‘’")
        agy = self._get_agy_path()
        if not agy:
            return {
                # Nội dung dựng sẵn, KHÔNG do AI sinh: gắn cờ để ứng dụng báo lỗi thay vì hiện như thật,
                # và để dictionary_cache không giữ nó 180 ngày.
                "is_fallback": True,
                "error": _ERR_NO_CLI,
                "word": clean_word,
                "workplace_scenario": {
                    "title": "Bối cảnh Công sở & Email chuyên nghiệp",
                    "dialogue_or_sentence": f"We need to carefully apply '{clean_word}' in our daily business communication.",
                    "meaning_vi": f"Ứng dụng từ '{clean_word}' trong giao dịch và công việc chuyên nghiệp.",
                    "usage_tip": "Dùng trong trao đổi với đồng nghiệp, đối tác hoặc văn bản trang trọng."
                },
                "casual_scenario": {
                    "title": "Bối cảnh Đời thường & Giao tiếp xã hội",
                    "dialogue_or_sentence": f"I used '{clean_word}' when talking to my friends today.",
                    "meaning_vi": f"Cách người bản xứ trò chuyện tự nhiên với từ '{clean_word}'.",
                    "usage_tip": "Thường dùng trong cuộc sống, đi cafe, du lịch hoặc tán gẫu thân mật."
                },
                "native_tip": f"Từ '{clean_word}' nên được dùng tự nhiên kết hợp với các cụm từ phổ biến."
            }

        prompt = (
            f"Bạn là một chuyên gia huấn luyện ngôn ngữ và giao tiếp tiếng Anh thực tế (Business & Daily Communication Coach).\n"
            f"Người học vừa lưu từ vựng sau từ tiểu thuyết và muốn BIẾT CÁCH ỨNG DỤNG TỪ NÀY VÀO ĐỜI SỐNG THỰC TẾ:\n"
            f"- Từ vựng: \"{clean_word}\"\n"
            f"- Từ loại: {part_of_speech or 'chưa rõ'}\n"
            f"- Cấp độ CEFR: {cefr_level or 'B2'}\n"
            f"- Nghĩa trong sách: {context_meaning_vi or 'chưa rõ'}\n\n"
            f"Nhiệm vụ của bạn là chuyển hóa từ này thành 2 tình huống thực tế sống động của thế kỷ 21 kèm mẹo bản ngữ:\n"
            f"1. Bối cảnh Công sở / Email / Họp hành chuyên nghiệp (Workplace & Professional Email).\n"
            f"2. Bối cảnh Đời sống hàng ngày / Trò chuyện bạn bè / Du lịch / Cafe (Casual & Social Daily Life).\n"
            f"3. Lời khuyên của người bản xứ (Native Tip) về sắc thái (nuance) và các lỗi người Việt hay gặp khi dùng từ này.\n\n"
            f"Hãy trả về định dạng JSON thuần túy sau:\n"
            f"{{\n"
            f"  \"word\": \"{clean_word}\",\n"
            f"  \"workplace_scenario\": {{\n"
            f"    \"title\": \"Tiêu đề tình huống công sở\",\n"
            f"    \"dialogue_or_sentence\": \"Câu hoặc đoạn ngắn bằng tiếng Anh\",\n"
            f"    \"meaning_vi\": \"Bản dịch tiếng Việt sát nghĩa, chuyên nghiệp\",\n"
            f"    \"usage_tip\": \"Mẹo áp dụng trong email/họp bằng tiếng Việt\"\n"
            f"  }},\n"
            f"  \"casual_scenario\": {{\n"
            f"    \"title\": \"Tiêu đề tình huống đời thường\",\n"
            f"    \"dialogue_or_sentence\": \"Câu hoặc hội thoại ngắn tiếng Anh đời thường\",\n"
            f"    \"meaning_vi\": \"Bản dịch tiếng Việt tự nhiên\",\n"
            f"    \"usage_tip\": \"Mẹo giao tiếp xã hội bằng tiếng Việt\"\n"
            f"  }},\n"
            f"  \"native_tip\": \"Lời khuyên sắc thái ngôn ngữ tinh tế từ chuyên gia bản xứ (bằng tiếng Việt)\"\n"
            f"}}\n"
            f"BẮT BUỘC: Chỉ trả về chuỗi JSON thuần túy, không có markdown formatting hay text thừa."
        )

        # Khoá gộp lượt đồng thời phải gồm MỌI đầu vào của prompt: chỉ theo từ thì hai người cùng tra
        # "bank" với nghĩa khác nhau nhận chung một kết quả, rồi route cache nó dưới khoá của người sau.
        inputs = "\x1f".join([clean_word.lower(), part_of_speech or "", cefr_level or "", context_meaning_vi or ""])
        parsed = await self._run_agy_json(
            prompt,
            timeout=35,
            priority=TaskPriority.INTERACTIVE,
            task_key=f"scenarios:{hashlib.sha1(inputs.encode('utf-8')).hexdigest()[:20]}"
        )
        if parsed and ("workplace_scenario" in parsed):
            return parsed
        logger.warning("Antigravity khong tra JSON hop le khi sinh tinh huong cho %s.", clean_word)

        return {
            "is_fallback": True,
            "error": _ERR_NO_SCENARIOS,
            "word": clean_word,
            "workplace_scenario": {
                "title": "Bối cảnh Công sở & Email",
                "dialogue_or_sentence": f"We should closely examine how '{clean_word}' can be strategically utilized.",
                "meaning_vi": f"Ứng dụng từ '{clean_word}' trong văn cảnh công việc và dự án.",
                "usage_tip": "Sử dụng trong email hoặc báo cáo chuyên môn."
            },
            "casual_scenario": {
                "title": "Bối cảnh Đời thường & Giao tiếp",
                "dialogue_or_sentence": f"It was interesting to see '{clean_word}' in real everyday life.",
                "meaning_vi": f"Cách dùng từ '{clean_word}' tự nhiên khi trò chuyện.",
                "usage_tip": "Dùng trong các cuộc tán gẫu thân mật."
            },
            "native_tip": f"Kết hợp '{clean_word}' với các động từ và giới từ quen thuộc của người bản xứ."
        }

    async def evaluate_user_sentence(
        self,
        word: str,
        user_sentence: str,
        context_type: Optional[str] = "daily"
    ) -> dict:
        """Chấm điểm, phân tích lỗi ngữ pháp và đưa ra phiên bản viết lại chuẩn người bản xứ cho câu người dùng tự đặt."""
        clean_word = word.strip().strip(".,!?;:\"'()[]{}“”‘’")
        clean_sentence = user_sentence.strip()
        agy = self._get_agy_path()
        if not agy:
            return {
                "word": clean_word,
                "user_sentence": clean_sentence,
                "is_correct": False,
                "score": 0,
                "feedback_vi": "",
                "corrected_sentence": "",
                "native_version": "",
                "native_explanation_vi": "",
                "is_fallback": True,
                "error": "Antigravity CLI chua san sang tren may chu."
            }

        prompt = (
            f"Bạn là một giảng viên bản xứ dạy viết tiếng Anh cao cấp (Native Writing Coach).\n"
            f"Học viên đang luyện tập đặt câu để ứng dụng từ vựng vào cuộc sống thực tế:\n"
            f"- Từ vựng mục tiêu: \"{clean_word}\"\n"
            f"- Câu của học viên tự viết: \"{clean_sentence}\"\n"
            f"- Ngữ cảnh mong muốn: {context_type or 'đời sống hàng ngày / công sở'}\n\n"
            f"Hãy phân tích và trả về định dạng JSON thuần túy như sau:\n"
            f"{{\n"
            f"  \"word\": \"{clean_word}\",\n"
            f"  \"user_sentence\": \"{clean_sentence}\",\n"
            f"  \"is_correct\": true/false,\n"
            f"  \"score\": 8,  // Thang điểm từ 1 đến 10 dựa trên độ chính xác ngữ pháp và độ tự nhiên\n"
            f"  \"feedback_vi\": \"Lời nhận xét chi tiết, ân cần bằng tiếng Việt: chỉ rõ lỗi ngữ pháp nếu có, cách dùng từ có hợp văn cảnh không\",\n"
            f"  \"corrected_sentence\": \"Câu sửa lỗi ngữ pháp cơ bản (giữ nguyên cấu trúc của học viên)\",\n"
            f"  \"native_version\": \"Phiên bản viết lại mượt mà, sang trọng chuẩn phong cách người bản xứ (Native Phrasing)\",\n"
            f"  \"native_explanation_vi\": \"Giải thích vì sao phiên bản bản xứ lại nghe tự nhiên và 'Tây' hơn bằng tiếng Việt\"\n"
            f"}}\n"
            f"BẮT BUỘC: Chỉ trả về chuỗi JSON thuần túy, không có giải thích thừa bên ngoài."
        )

        parsed = await self._run_agy_json(
            prompt,
            timeout=35,
            priority=TaskPriority.INTERACTIVE
        )
        if parsed and ("score" in parsed and "feedback_vi" in parsed):
            return parsed
        logger.warning("Antigravity khong tra JSON hop le khi cham cau cho %s.", clean_word)

        return {
            "word": clean_word,
            "user_sentence": clean_sentence,
            "is_correct": False,
            "score": 0,
            "feedback_vi": "",
            "corrected_sentence": "",
            "native_version": "",
            "native_explanation_vi": "",
            "is_fallback": True,
            "error": "Khong cham duoc cau nay. Vui long thu lai."
        }

    async def generate_scenario_quiz(
        self,
        words: List[str]
    ) -> dict:
        """Sinh câu đố trắc nghiệm tình huống đời sống kiểm tra phản xạ chọn từ của người học."""
        word_list = [w.strip() for w in words if w.strip()][:5]
        if not word_list:
            word_list = ["serendipity", "ephemeral", "scrutinize"]
        words_str = ", ".join(word_list)

        agy = self._get_agy_path()
        if not agy:
            return {"questions": []}

        prompt = (
            f"Bạn là chuyên gia thiết kế bài kiểm tra phản xạ giao tiếp tiếng Anh thực tế.\n"
            f"Dựa trên danh sách từ vựng sau mà học viên cần ôn tập: {words_str}\n\n"
            f"Hãy tạo 3 câu hỏi trắc nghiệm tình huống đời sống (Email công việc, đàm phán, nói chuyện đời thường) để kiểm tra xem học viên có biết khi nào nên dùng từ này không.\n"
            f"Định dạng JSON chuẩn:\n"
            f"{{\n"
            f"  \"questions\": [\n"
            f"    {{\n"
            f"      \"id\": 1,\n"
            f"      \"scenario\": \"Tình huống thực tế ngắn bằng tiếng Việt (ví dụ: Bạn gửi email cập nhật dự án cho sếp...)\",\n"
            f"      \"target_word\": \"từ_mục_tiêu\",\n"
            f"      \"question\": \"Câu hỏi lựa chọn phương án bằng tiếng Việt\",\n"
            f"      \"options\": [\n"
            f"        \"A. Lựa chọn A (tiếng Anh)\",\n"
            f"        \"B. Lựa chọn B (tiếng Anh)\",\n"
            f"        \"C. Lựa chọn C (tiếng Anh)\",\n"
            f"        \"D. Lựa chọn D (tiếng Anh)\"\n"
            f"      ],\n"
            f"      \"correct_answer_index\": 0,\n"
            f"      \"explanation_vi\": \"Giải thích ngắn gọn tại sao phương án đó chuẩn xác nhất\"\n"
            f"    }}\n"
            f"  ]\n"
            f"}}\n"
            f"Chỉ trả về JSON thuần túy."
        )

        parsed = await self._run_agy_json(
            prompt,
            timeout=35,
            priority=TaskPriority.INTERACTIVE,
            task_key=f"quiz:{words_str}"
        )
        if parsed and ("questions" in parsed):
            return parsed
        logger.warning("Antigravity khong tra JSON hop le khi sinh quiz tinh huong.")

        return {"questions": []}

