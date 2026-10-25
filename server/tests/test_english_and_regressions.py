"""Test hồi quy cho các lỗi đã được vá + hợp đồng API của chế độ học tiếng Anh."""

import asyncio
import pytest
from fastapi.testclient import TestClient

from server import app
from services.auth_token import issue_token
from database import SqliteBackedTable
from services.translation_worker import translation_worker
from services.json_utils import extract_json_object
from services.dictionary_cache import DictionaryCache
from routes.tts import is_english, is_chinese
from services.tts_voice_service import resolve_tts_plan

client = TestClient(app, headers={"Authorization": f"Bearer {issue_token('test_english', 'test_english')}"})


# ---------------------------------------------------------------------------
# HỒI QUY 1: translate_chapter_titles_batch từng trả về None
# ---------------------------------------------------------------------------

def test_titles_batch_always_returns_list_same_length():
    """Trước đây hàm này rơi ra khỏi vòng lặp mà không return -> None.
    Hậu quả: len(None) làm 500 ở GET /v1/novels/{id}."""
    titles = ["第一章 初始", "第二章 风起", "第三章 云涌"]
    res = asyncio.run(translation_worker.translate_chapter_titles_batch(titles))
    assert res is not None, "KHÔNG ĐƯỢC trả về None"
    assert isinstance(res, list)
    assert len(res) == len(titles), "Phải giữ đúng số lượng chương, không bỏ sót"
    assert all(isinstance(t, str) and t.strip() for t in res)


def test_titles_batch_empty_input():
    assert asyncio.run(translation_worker.translate_chapter_titles_batch([])) == []


def test_titles_batch_survives_cli_failure(monkeypatch):
    """Khi CLI lỗi, vẫn phải trả về đủ tên chương (dạng dự phòng)."""
    monkeypatch.setattr(translation_worker, "_get_agy_path", lambda: "/bin/false")

    async def boom(*a, **k):
        raise RuntimeError("CLI chết")

    monkeypatch.setattr(translation_worker, "_run_agy", boom)
    titles = ["第一章 甲", "第二章 乙"]
    res = asyncio.run(translation_worker.translate_chapter_titles_batch(titles))
    assert isinstance(res, list) and len(res) == 2


# ---------------------------------------------------------------------------
# HỒI QUY 2: khóa chính reading_progress sinh dòng mới mỗi lần sync
# ---------------------------------------------------------------------------

def test_progress_pkey_is_stable(tmp_path):
    t = SqliteBackedTable("reading_progress", db_path=str(tmp_path / "t.db"))
    a = {"novel_id": "n1", "current_chapter": 5, "last_read_at": "2026-01-01T00:00:00Z"}
    b = {"novel_id": "n1", "current_chapter": 9, "last_read_at": "2026-01-02T00:00:00Z"}
    assert t._extract_pkey(a) == t._extract_pkey(b), "Cùng truyện+user phải cùng một khóa"


def test_progress_pkey_separates_users(tmp_path):
    t = SqliteBackedTable("reading_progress", db_path=str(tmp_path / "t.db"))
    a = {"novel_id": "n1", "current_chapter": 5, "user_id": "u1"}
    b = {"novel_id": "n1", "current_chapter": 5, "user_id": "u2"}
    assert t._extract_pkey(a) != t._extract_pkey(b)


def test_fallback_pkey_deterministic(tmp_path):
    """hash() của Python bị randomize theo process nên không dùng làm khóa bền vững."""
    t = SqliteBackedTable("misc_table", db_path=str(tmp_path / "t.db"))
    row = {"alpha": 1, "beta": "hai"}
    assert t._extract_pkey(row) == t._extract_pkey(dict(row))
    assert t._extract_pkey(row).startswith("auto_")


def test_progress_sync_does_not_duplicate_rows(tmp_path):
    t = SqliteBackedTable("reading_progress_dup", db_path=str(tmp_path / "t.db"))
    for ch in range(1, 6):
        t.upsert({"novel_id": "n9", "user_id": "u1", "current_chapter": ch,
                  "last_read_at": f"2026-01-0{ch}T00:00:00Z"}).execute()
    rows = t.select().eq("novel_id", "n9").execute().data
    assert len(rows) == 1, f"Phải còn đúng 1 dòng, đang có {len(rows)}"
    assert rows[0]["current_chapter"] == 5, "Phải giữ giá trị mới nhất"


# ---------------------------------------------------------------------------
# HỒI QUY 3: adapt-chapter cắt cứng 35 đoạn
# ---------------------------------------------------------------------------

def test_adapt_chapter_never_truncates(monkeypatch):
    """Chương dài phải trả về đủ số đoạn, không im lặng cắt mất."""
    monkeypatch.setattr(translation_worker, "_get_agy_path", lambda: None)
    paras = [f"Đoạn số {i}." for i in range(80)]
    res = asyncio.run(translation_worker.adapt_to_english_chapter("Chương thử", paras))
    assert len(res["paragraphs_en"]) == 80, "Không được mất đoạn nào"
    assert res["is_fallback"] is True


def test_adapt_chapter_chunks_and_keeps_order(monkeypatch):
    monkeypatch.setattr(translation_worker, "_get_agy_path", lambda: "/usr/bin/true")

    async def fake_json(prompt, timeout=30, **_kw):
        # Đếm số đoạn yêu cầu trong prompt để trả về đúng số phần tử
        import re as _re
        m = _re.search(r"DUNG (\d+) doan", prompt)
        n = int(m.group(1))
        idx = prompt.split("Noi dung:\n", 1)[1].split("\n\n")
        return {
            "title_en": "The Test Chapter",
            "paragraphs_en": [f"EN::{p}" for p in idx[:n]],
            "paragraphs_vi": idx[:n],
        }

    monkeypatch.setattr(translation_worker, "_run_agy_json", fake_json)
    paras = [f"Doan {i}" for i in range(50)]
    res = asyncio.run(translation_worker.adapt_to_english_chapter("T", paras))
    assert len(res["paragraphs_en"]) == 50
    assert res["paragraphs_en"][0] == "EN::Doan 0"
    assert res["paragraphs_en"][-1] == "EN::Doan 49", "Thứ tự các lô phải được giữ nguyên"
    assert res["failed_chunks"] == 0
    assert res["is_fallback"] is False


# ---------------------------------------------------------------------------
# HỒI QUY 4: parse JSON của LLM bằng regex greedy
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("raw,expected", [
    ('{"a": 1}', {"a": 1}),
    ('```json\n{"a": 1}\n```', {"a": 1}),
    ('Đây là kết quả:\n{"a": 1}\nHết.', {"a": 1}),
    ('{"a": "chuỗi có } ngoặc"}', {"a": "chuỗi có } ngoặc"}),
    ('{"a": {"b": 2}} rác phía sau {khác}', {"a": {"b": 2}}),
    ('không có json ở đây', None),
    ('', None),
    ('{"hỏng": ', None),
])
def test_extract_json_object(raw, expected):
    assert extract_json_object(raw) == expected


def test_extract_json_handles_escaped_quotes():
    assert extract_json_object(r'{"a": "có \" dấu nháy"}') == {"a": 'có " dấu nháy'}


# ---------------------------------------------------------------------------
# HỒI QUY 5: TTS đọc từ đơn tiếng Anh bằng giọng Việt
# ---------------------------------------------------------------------------

def test_single_english_word_detected_as_english():
    assert is_english("ephemeral") is True
    assert is_english("serendipity") is True


def test_explicit_lang_always_wins():
    assert is_english("ephemeral", lang="vi") is False
    assert is_english("Cô ấy mỉm cười.", lang="en") is True


def test_vietnamese_sentence_still_vietnamese():
    assert is_english("Cô ấy khẽ mỉm cười rồi quay đi.") is False


def test_english_word_is_rendered_with_the_chosen_local_voice():
    plan = resolve_tts_plan("ephemeral", "mai_phuong", "en", "/tmp/x")
    assert plan.lang == "en" and plan.kind == "render" and plan.preset_name == "Mỹ Duyên"


def test_vietnamese_paragraph_is_rendered_with_the_chosen_local_voice():
    plan = resolve_tts_plan("Cô ấy khẽ mỉm cười.", "mai_phuong", "vi", "/tmp/x")
    assert plan.lang == "vi" and plan.kind == "render" and plan.preset_name == "Mỹ Duyên"


def test_chinese_text_detected_and_left_to_the_device_voice():
    assert is_chinese("这是一场注定碾压的死战") is True
    for voice in ("mai_phuong", "dinh_trong"):
        plan = resolve_tts_plan("这是一场注定碾压的死战", voice, None, "/tmp/x")
        assert plan.lang == "zh" and plan.kind == "device"


def test_chapter11_english_paragraphs_remain_english():
    p0 = "This was destined to be a rout. No matter how vastly Li Tianming had once outstripped him in martial prowess, three wasted years had reduced his cultivation to its lowest ebb."
    p1 = "A solitary, first-tier lifebound beast could alter nothing."
    p2 = "Not a soul among them believed that Li Tianming possessed even the faintest power to fight back."
    p3 = "Today, he was fated to play the tragic fool —destined only to be dragged away in disgrace, forced to watch Li Zifeng..."
    for p in [p0, p1, p2, p3]:
        assert is_english(p) is True
        plan = resolve_tts_plan(p, "mai_phuong", None, "/tmp/x")
        assert plan.lang == "en" and plan.kind == "render", f"Paragraph '{p[:30]}...' must be rendered as English"


def test_english_ratio_and_time_not_corrupted_to_vietnamese():
    text = "At 10:30, the enemy retreated with 50% casualties."
    assert is_english(text) is True
    plan = resolve_tts_plan(text, "mai_phuong", None, "/tmp/x")
    assert plan.lang == "en"
    assert "trên" not in plan.norm_text and "phần trăm" not in plan.norm_text
    assert "10:30" in plan.norm_text and "50%" in plan.norm_text, "VieNeu tự đọc giờ và phần trăm"


def test_english_voice_keeps_the_gender_of_the_chosen_voice():
    male = resolve_tts_plan("Li Tianming attacked.", "dinh_trong", None, "/tmp/x")
    female = resolve_tts_plan("Li Tianming attacked.", "mai_phuong", None, "/tmp/x")
    assert (male.preset_name, female.preset_name) == ("Đức Trí", "Mỹ Duyên")


# ---------------------------------------------------------------------------
# HỒI QUY 6: audio_url không URL-encode
# ---------------------------------------------------------------------------

def test_synthesize_url_is_encoded():
    res = client.post("/v1/tts/synthesize", json={
        "text": "Tom & Jerry #1 + bạn", "voice": "mai_phuong", "lang": "vi"
    })
    assert res.status_code == 200
    url = res.json()["audio_url"]
    assert "&voice=" in url
    assert "Tom & Jerry" not in url, "Ký tự & phải được mã hóa, nếu không query string sẽ vỡ"
    assert "%26" in url or "Tom+%26" in url


# ---------------------------------------------------------------------------
# Bộ nhớ đệm từ điển
# ---------------------------------------------------------------------------

def test_dictionary_cache_roundtrip(tmp_path):
    c = DictionaryCache(db_path=str(tmp_path / "d.db"))
    k = c.make_key("explain_word", "Ephemeral", "the ephemeral light", "Boston")
    assert c.get(k) is None
    c.put(k, "explain_word", "ephemeral", {"word": "ephemeral", "cefr_level": "C1"})
    got = c.get(k)
    assert got["cefr_level"] == "C1"
    assert c.stats()["entries"] == 1


def test_dictionary_cache_key_is_case_insensitive(tmp_path):
    c = DictionaryCache(db_path=str(tmp_path / "d.db"))
    assert c.make_key("explain_word", "Ephemeral", "CTX") == c.make_key("explain_word", "ephemeral", "ctx")


def test_dictionary_cache_never_stores_fallback(tmp_path):
    """Nếu cache cả kết quả dự phòng thì dữ liệu rỗng sẽ bị đóng băng vĩnh viễn."""
    c = DictionaryCache(db_path=str(tmp_path / "d.db"))
    k = c.make_key("explain_word", "x")
    c.put(k, "explain_word", "x", {"word": "x", "is_fallback": True})
    assert c.get(k) is None


# ---------------------------------------------------------------------------
# Hợp đồng API chế độ học tiếng Anh
# ---------------------------------------------------------------------------

def test_explain_word_contract():
    res = client.post("/v1/english/explain-word", json={"word": "ephemeral"})
    assert res.status_code == 200
    d = res.json()
    for field in ("word", "ipa_us", "ipa_uk", "part_of_speech", "cefr_level",
                  "context_meaning_vi", "english_definition", "collocations",
                  "example_sentences"):
        assert field in d, f"thiếu trường {field}"
    assert isinstance(d["collocations"], list)


def test_explain_word_never_fabricates_ipa_on_failure():
    """Khi không phân tích được, IPA phải RỖNG chứ không phải '/word/' bịa ra."""
    res = client.post("/v1/english/explain-word", json={"word": "zzzqqxyw"})
    d = res.json()
    if d.get("is_fallback"):
        assert d["ipa_us"] == "" and d["ipa_uk"] == ""
        assert d["english_definition"] == ""


def test_explain_words_batch_contract():
    res = client.post("/v1/english/explain-words-batch",
                      json={"words": ["ephemeral", "serendipity"]})
    assert res.status_code == 200
    d = res.json()
    assert d["total"] == 2
    assert len(d["results"]) == 2


def test_explain_words_batch_caps_at_20():
    res = client.post("/v1/english/explain-words-batch",
                      json={"words": [f"w{i}" for i in range(40)]})
    assert res.json()["total"] <= 20


def test_evaluate_sentence_does_not_fake_a_score():
    """Trước đây khi lỗi vẫn trả score=7/8 kèm nhận xét khen — chấm điểm giả."""
    res = client.post("/v1/english/evaluate-sentence",
                      json={"word": "ephemeral", "sentence": "asdf qwer zxcv"})
    d = res.json()
    if d.get("is_fallback"):
        assert d["score"] == 0
        assert d["feedback_vi"] == ""


def test_cache_stats_endpoint():
    res = client.get("/v1/english/cache/stats")
    assert res.status_code == 200
    assert "entries" in res.json()
