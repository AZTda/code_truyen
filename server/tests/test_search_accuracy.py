"""Ghim hành vi tìm kiếm song ngữ Việt/Trung độ chính xác cao."""

import time
import pytest
from models.novel import Novel
from services.text_similarity import strip_accents, similarity_vi, similarity_cjk
from services.search_ranker import score_novel, rank_results, top_web_score
from services.search_service import SearchService, LOCAL_FUZZY_THRESHOLD
from services.library_index import library_index
from services.hanviet_dict import transliterate_to_hanviet, has_cjk
from services.query_translator import query_translator


def _novel(id, title, title_vi="", author="", source_id="test"):
    return Novel(id=id, title=title, title_vi=title_vi, author=author,
                 url=f"https://example.com/{id}", source_id=source_id)


def _entry(id, title, title_vi, author=""):
    t_vi_no = strip_accents(title_vi)
    t_no = strip_accents(title)
    return {
        "novel": {"id": id, "title": title, "title_vi": title_vi, "author": author},
        "id": id,
        "title_lower": title.lower(),
        "title_vi": title_vi,
        "title_vi_lower": title_vi.lower(),
        "author_lower": author.lower(),
        "author_no_acc": strip_accents(author),
        "title_no_acc": t_no,
        "title_vi_no_acc": t_vi_no,
        "word_set": set(t_vi_no.split()) | set(t_no.split()),
    }


LIBRARY = [
    _entry("n1", "剑来", "Kiếm Lai", "Phong Hỏa Hí Chư Hầu"),
    _entry("n2", "斗破苍穹", "Đấu Phá Thương Khung", "Thiên Tằm Thổ Đậu"),
    _entry("n3", "波士顿爱情故事", "Chuyện Tình Boston", "Như Tâm"),
]


@pytest.fixture
def seeded_library(monkeypatch):
    monkeypatch.setattr(library_index, "_entries", LIBRARY)
    monkeypatch.setattr(library_index, "_built_at", time.monotonic())
    return SearchService()


# ---------- Độ tương đồng tiếng Việt ----------

def test_similarity_vi_accent_insensitive():
    assert similarity_vi(strip_accents("Kiếm Lai"), strip_accents("kiem lai")) == 1.0

def test_similarity_vi_small_typo_passes_threshold():
    assert similarity_vi("kiem lia", "kiem lai") >= LOCAL_FUZZY_THRESHOLD

def test_similarity_vi_word_order():
    assert similarity_vi("lai kiem", "kiem lai") >= 0.85

def test_similarity_vi_unrelated_low():
    assert similarity_vi("dau pha thuong khung", "kiem lai") < 0.3


# ---------- Độ tương đồng tiếng Trung ----------

def test_similarity_cjk_exact_and_substring():
    assert similarity_cjk("剑来", "剑来") == 1.0
    assert similarity_cjk("剑来", "剑来（精校版）") >= 0.9
    assert similarity_cjk("斗破苍穹", "剑来") < 0.3


# ---------- Chấm điểm truyện & kiểm định ngược Hán-Việt ----------

def test_score_novel_vietnamese_query():
    n = _novel("x", "剑来", "Kiếm Lai")
    assert score_novel("kiem lai", n) >= 0.9
    assert score_novel("kiếm lai", n) >= 0.9
    assert score_novel("chuyen tinh boston", n) < 0.3

def test_score_novel_chinese_query():
    n = _novel("x", "剑来", "Kiếm Lai")
    assert score_novel("剑来", n) == 1.0

def test_score_novel_hanviet_backcheck():
    """Kết quả web chỉ có tựa Trung (title_vi rỗng) vẫn phải khớp từ khóa Việt
    nhờ phiên âm Hán-Việt của tựa gốc."""
    hv = transliterate_to_hanviet("斗破苍穹")
    if not hv or has_cjk(hv):
        pytest.skip("Bộ chữ chưa đủ trong HANVIET_CHAR_MAP")
    n = _novel("x", "斗破苍穹", title_vi="")
    assert score_novel(hv, n) >= 0.9

def test_rank_results_local_first_then_score():
    web_wrong = _novel("w1", "斗破苍穹", "Đấu Phá Thương Khung")
    web_right = _novel("w2", "剑来", "Kiếm Lai")
    local = _novel("l1", "遮天", "Già Thiên", source_id="local")
    ranked = rank_results("kiem lai", [web_wrong, web_right, local])
    assert ranked[0].id == "l1"          # local luôn trên đầu
    assert ranked[1].id == "w2"          # đúng truyện xếp trước
    assert ranked[2].id == "w1"

def test_top_web_score_ignores_local():
    local = _novel("l1", "剑来", "Kiếm Lai", source_id="local")
    assert top_web_score("kiem lai", [local]) is None


# ---------- Tìm mờ trong thư viện ----------

def test_local_search_no_accent(seeded_library):
    res = seeded_library.search_local_library("kiem lai")
    assert res and res[0].id == "n1"

def test_local_search_typo(seeded_library):
    res = seeded_library.search_local_library("kiem lia")
    assert any(n.id == "n1" for n in res)

def test_local_search_cjk_query(seeded_library):
    res = seeded_library.search_local_library("剑来")
    assert res and res[0].id == "n1"
    assert not any(n.id == "n2" for n in res)

def test_local_search_ranking(seeded_library):
    res = seeded_library.search_local_library("chuyen tinh boston")
    assert res and res[0].id == "n3"

def test_local_search_no_noise(seeded_library):
    assert seeded_library.search_local_library("xyzabc quw") == []

def test_local_search_speed(monkeypatch):
    entries = [_entry(f"id{i}", f"小说{i}", f"Tiểu Thuyết Số {i}", f"Tác Giả {i}") for i in range(200)]
    monkeypatch.setattr(library_index, "_entries", entries)
    monkeypatch.setattr(library_index, "_built_at", time.monotonic())
    svc = SearchService()
    start = time.perf_counter()
    svc.search_local_library("tieu thuyet so 42")
    assert time.perf_counter() - start < 0.05


# ---------- Đường AI chuyên sâu ----------

@pytest.fixture
def isolated_translator(monkeypatch):
    """Không để test ghi vào truyencloud.db thật; dọn cache bộ nhớ sau test."""
    monkeypatch.setattr(query_translator, "_persist_translation", lambda *a, **k: None)
    touched = []
    yield touched
    for key in touched:
        query_translator._cache.pop(key, None)
        query_translator._neg_cache.pop(key, None)


@pytest.mark.asyncio
async def test_translate_query_via_ai_success(monkeypatch, isolated_translator):
    async def fake_agy(prompt, timeout, model, priority):
        return "剑来"
    monkeypatch.setattr(query_translator, "_execute_agy_process", fake_agy)
    q = "ai query success case"
    isolated_translator.append(q)
    res = await query_translator.translate_query_via_ai(q)
    assert "剑来" in res
    # Cache được ghi đè để lượt tìm sau dùng ngay
    assert query_translator._cache.get(q) == res

@pytest.mark.asyncio
async def test_translate_query_via_ai_uncertain_guard(monkeypatch, isolated_translator):
    async def fake_agy(prompt, timeout, model, priority):
        return "KHONG_RO"
    monkeypatch.setattr(query_translator, "_execute_agy_process", fake_agy)
    q = "ai query unknown case"
    isolated_translator.append(q)
    res = await query_translator.translate_query_via_ai(q)
    assert res == [q]
    assert q not in query_translator._cache

@pytest.mark.asyncio
@pytest.mark.parametrize("refusal", ["我不确定这本书", "抱歉，无法回答。", "这是一个很长很长的中文句子而不是书名呢"])
async def test_translate_query_via_ai_rejects_chinese_refusals(monkeypatch, isolated_translator, refusal):
    async def fake_agy(prompt, timeout, model, priority):
        return refusal
    monkeypatch.setattr(query_translator, "_execute_agy_process", fake_agy)
    q = "ai query refusal case"
    isolated_translator.append(q)
    res = await query_translator.translate_query_via_ai(q)
    assert res == [q]
    assert q not in query_translator._cache

@pytest.mark.asyncio
async def test_translate_query_cjk_passthrough():
    res = await query_translator.translate_query_via_ai("剑来")
    assert "剑来" in res
