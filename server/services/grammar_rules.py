"""Danh mục mẫu cấu trúc ngữ pháp (CEFR B2-C2) dạng regex, dùng bởi services/grammar_extractor.py.

Mọi regex ở đây chạy trên văn bản do người dùng gửi nên phải có chi phí tuyến tính: không dùng
`[a-zA-Z\\s]+` liền kề `\\s+` hay `[^,]+` không chặn (lồng lặp chồng nhau -> O(n^2) trên câu dài không dấu
câu). Số từ giữa hai mốc được chặn bằng `{m,n}`; câu dài hơn MAX_SENTENCE_CHARS không được quét.
"""

import re

# Curated catalog of rule-based grammar patterns (CEFR B2-C2)
GRAMMAR_RULES = [
    {
        "id": "negative_inversion",
        "pattern": "Negative Adverbial + Auxiliary + Subject",
        "pattern_name": "Đảo ngữ với phó từ phủ định (Negative Inversion)",
        "category": "Inversion",
        "explanation_vi": "Nhấn mạnh mức độ hiếm hoi hoặc tính bất ngờ. Đưa phó từ phủ định lên đầu câu và đảo trợ động từ lên trước chủ ngữ.",
        "regex": re.compile(
            r"\b(?:Not only|Hardly|Scarcely|Barely|No sooner|Seldom|Rarely|Little|Under no circumstances|In no way)\s+(?:did|had|was|were|could|would|will|does|do)\s+[a-zA-Z]+",
            re.IGNORECASE,
        ),
    },
    {
        "id": "inverted_conditional_had",
        "pattern": "Had + Subject + Past Participle",
        "pattern_name": "Đảo ngữ câu điều kiện loại 3 (Inverted 3rd Conditional)",
        "category": "Conditionals",
        "explanation_vi": "Văn phong trang trọng (formal/literary) thay thế cho 'If + S + had + V3'. Thường gặp trong văn chương cổ điển và tiểu thuyết tu tiên/huyền ảo.",
        "regex": re.compile(
            r"\bHad\s+[A-Z][a-z]*\s+(?:been|known|seen|realized|understood|felt|taken|given|heard|noticed|chosen|[a-z]+(?:ed|en))\b",
        ),
    },
    {
        "id": "inverted_conditional_were",
        "pattern": "Were + Subject + (to) Verb",
        "pattern_name": "Đảo ngữ câu điều kiện loại 2 (Inverted 2nd Conditional)",
        "category": "Conditionals",
        "explanation_vi": "Diễn tả giả định trái với hiện tại hoặc khả năng khó xảy ra ở tương lai bằng phong thái trang nhã.",
        "regex": re.compile(
            r"\bWere\s+[A-Z][a-z]*\s+(?:to\s+[a-z]+|not\s+for|it\s+not)\b",
        ),
    },
    {
        "id": "cleft_sentence_it",
        "pattern": "It is / was + Focus + that / who",
        "pattern_name": "Câu chẻ nhấn mạnh (It-Cleft Sentence)",
        "category": "Cleft & Emphasis",
        "explanation_vi": "Tách một vế câu để làm nổi bật tân ngữ, trạng ngữ hoặc chủ ngữ trung tâm hành động.",
        "regex": re.compile(
            r"\bIt\s+(?:is|was)\s+(?:(?:the|this)\s+(?:[a-zA-Z]+\s+){0,11}[a-zA-Z]+|[A-Z][a-z]+)\s+(?:that|who|whom)\s+[a-zA-Z]+",
            re.IGNORECASE,
        ),
    },
    {
        "id": "cleft_sentence_wh",
        "pattern": "What + Clause + is / was",
        "pattern_name": "Câu chẻ dạng Wh (Wh-Cleft / Pseudo-cleft)",
        "category": "Cleft & Emphasis",
        "explanation_vi": "Dùng mệnh đề 'What...' đứng đầu làm chủ ngữ để thu hút sự chú ý vào thông tin theo sau to be.",
        "regex": re.compile(
            r"\bWhat\s+(?:he|she|they|I|we|the\s+[a-z]+)\s+(?:(?:really|truly|actually)\s+)?(?:wanted|needed|saw|felt|sought|desired)\s+(?:is|was)\b",
            re.IGNORECASE,
        ),
    },
    {
        "id": "participle_clause_having",
        "pattern": "Having + Past Participle, Clause",
        "pattern_name": "Mệnh đề phân từ hoàn thành (Perfect Participle Clause)",
        "category": "Participle Clauses",
        "explanation_vi": "Rút gọn mệnh đề trạng ngữ chỉ nguyên nhân hoặc thời gian, hành động phân từ diễn ra và hoàn tất trước hành động chính.",
        "regex": re.compile(
            r"\bHaving\s+(?:been|seen|reached|absorbed|broken|refined|mastered|gathered|[a-z]+(?:ed|en))\b",
            re.IGNORECASE,
        ),
    },
    {
        "id": "participle_clause_ing",
        "pattern": "Present Participle (V-ing) as adverbial clause",
        "pattern_name": "Mệnh đề hiện tại phân từ (Present Participle Clause)",
        "category": "Participle Clauses",
        "explanation_vi": "Hai hành động xảy ra đồng thời hoặc liên tiếp nhanh chóng của cùng một chủ ngữ được rút gọn thành V-ing đầu câu.",
        "regex": re.compile(
            r"^(?:Looking|Glancing|Sensing|Realizing|Breathing|Standing|Stepping|Holding|Gazing|Whispering)\s+[a-zA-Z\s]+,",
        ),
    },
    {
        "id": "locative_inversion",
        "pattern": "Prepositional Phrase + Verb + Subject",
        "pattern_name": "Đảo ngữ chỉ phương hướng / nơi chốn (Locative Inversion)",
        "category": "Inversion",
        "explanation_vi": "Đưa cụm giới từ chỉ không gian hoặc phương hướng lên trước động từ chuyển động/vị trí để tạo hiệu ứng thị giác sinh động.",
        "regex": re.compile(
            r"^(?:Into|Upon|Across|Beneath|Through|Down|Beyond|Above)\s+the\s+(?:[a-zA-Z]+\s+){1,12}(?:came|stood|lay|walked|stepped|flew|rose)\s+[a-zA-Z]+",
            re.IGNORECASE,
        ),
    },
    {
        "id": "comparative_correlative",
        "pattern": "The + comparative ..., the + comparative ...",
        "pattern_name": "So sánh kép tăng tiến (Comparative Correlative)",
        "category": "Comparison",
        "explanation_vi": "Diễn tả mối quan hệ tỷ lệ thuận hoặc nghịch giữa hai quá trình (càng... càng...).",
        "regex": re.compile(
            r"\bThe\s+(?:more|greater|harder|longer|further|higher|deeper|darker|stronger|fiercer)\b[^,]{1,200},\s+the\s+(?:more|greater|harder|longer|further|higher|deeper|darker|stronger|fiercer)\b",
            re.IGNORECASE,
        ),
    },
    {
        "id": "passive_reporting",
        "pattern": "Subject + is / was + said / believed / thought + to + Verb",
        "pattern_name": "Bị động với động từ tường thuật (Reporting Passive)",
        "category": "Passive Voice",
        "explanation_vi": "Diễn đạt tin đồn, nhận định chung hoặc truyền thuyết trong tiểu thuyết một cách khách quan, huyền bí.",
        "regex": re.compile(
            r"\b(?:is|are|was|were)\s+(?:said|believed|thought|rumored|reported|considered|alleged)\s+to\s+(?:be|have\s+been|[a-z]+)\b",
            re.IGNORECASE,
        ),
    },
    {
        "id": "subjunctive_mood",
        "pattern": "Verb/Adj of urgency + that + Subject + (should) bare-inf",
        "pattern_name": "Thức giả định hiện tại (Present Subjunctive)",
        "category": "Subjunctive",
        "explanation_vi": "Dùng sau các động từ/tính từ chỉ mệnh lệnh, yêu cầu hoặc tính cấp thiết (crucial, demand, insist that...).",
        "regex": re.compile(
            r"\b(?:insist|demand|recommend|suggest|order|require|command|essential|vital|imperative|crucial)\s+that\s+[a-zA-Z]+\s+(?:be|[a-z]+(?<!s))\b",
            re.IGNORECASE,
        ),
    },
]
