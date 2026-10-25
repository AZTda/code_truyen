"""Test end-to-end pipeline tìm kiếm song ngữ với scraper/translator/cache giả.

Không có mạng, không đụng truyencloud.db: mọi phụ thuộc ngoài đều được thay.
"""

import asyncio
import time
from types import SimpleNamespace

import pytest

import services.search_service as ss
from models.novel import Novel
from services.library_index import library_index
from services.query_translator import query_translator
from services.search_ranker import rank_results
from services.text_similarity import strip_accents


def _novel(id, title, title_vi="", source_id="69shuba"):
    return Novel(id=id, title=title, title_vi=title_vi, author="",
                 url=f"https://example.com/{id}", source_id=source_id)


def _entry(id, title, title_vi):
    t_vi_no, t_no = strip_accents(title_vi), strip_accents(title)
    return {
        "novel": {"id": id, "title": title, "title_vi": title_vi, "author": ""},
        "id": id, "title_lower": title.lower(), "title_vi": title_vi,
        "title_vi_lower": title_vi.lower(), "author_lower": "", "author_no_acc": "",
        "title_no_acc": t_no, "title_vi_no_acc": t_vi_no,
        "word_set": set(t_vi_no.split()) | set(t_no.split()),
    }


class FakeCache:
    def __init__(self, preset=None):
        self.store = dict(preset or {})
        self.deleted = []

    def get(self, q, source=None):
        return self.store.get((q, source))

    def set(self, q, novels, source=None, ttl_seconds=0):
        self.store[(q, source)] = list(novels)

    def delete_for_query(self, q, sources):
        self.deleted.extend((q, s) for s in sources)


@pytest.fixture
def env(monkeypatch):
    """Dựng môi trường giả: thư viện rỗng, cache giả, registry giả."""
    monkeypatch.setattr(library_index, "_entries", [])
    monkeypatch.setattr(library_index, "_built_at", time.monotonic())
    monkeypatch.setattr(library_index, "_dirty", False)
    cache = FakeCache()
    monkeypatch.setattr(ss, "search_cache_service", cache)
    monkeypatch.setattr(ss, "registry", SimpleNamespace(
        get_by_source_id=lambda sid: SimpleNamespace(SOURCE_ID=sid)))
    svc = ss.SearchService()
    refines = []
    monkeypatch.setattr(svc, "_schedule_ai_refine", lambda *a, **k: refines.append(a))
    return SimpleNamespace(svc=svc, cache=cache, refines=refines, mp=monkeypatch)


def _translator(env, terms):
    async def translate_query(q):
        return list(terms)
    env.mp.setattr(ss, "query_translator", SimpleNamespace(translate_query=translate_query))


def _scrapers(env, by_term, fail=False):
    """by_term: {term: [novels]} — mọi nguồn trả cùng danh sách cho một term."""
    calls = []

    async def fake(scraper, term, timeout=3.5):
        calls.append((scraper.SOURCE_ID, term))
        if fail:
            return None
        return [n.model_copy() for n in by_term.get(term, [])]
    env.mp.setattr(env.svc, "_search_scraper_with_timeout", fake)
    return calls


# ---------- search() end-to-end ----------

@pytest.mark.asyncio
async def test_search_ranks_correct_novel_first(env):
    _translator(env, ["剑来"])
    _scrapers(env, {"剑来": [_novel("w1", "斗破苍穹", "Đấu Phá Thương Khung"), _novel("w2", "剑来", "Kiếm Lai")]})
    res = await env.svc.search("kiem lai")
    assert res.results[0].id == "w2"
    assert env.refines == []  # độ tin cậy cao -> không cần AI tinh chỉnh


@pytest.mark.asyncio
async def test_search_tries_alternate_term_on_low_confidence(env):
    _translator(env, ["错误词", "剑来"])
    calls = _scrapers(env, {"错误词": [_novel("w1", "无关小说", "Vô Quan")], "剑来": [_novel("w2", "剑来", "Kiếm Lai")]})
    res = await env.svc.search("kiem lai")
    assert any(term == "剑来" for _, term in calls)
    assert res.results[0].id == "w2"


@pytest.mark.asyncio
async def test_search_schedules_refine_when_still_low_confidence(env):
    _translator(env, ["错误词"])
    _scrapers(env, {"错误词": [_novel("w1", "无关小说", "Vô Quan Tiểu Thuyết")]})
    await env.svc.search("kiem lai truyen")
    assert len(env.refines) == 1


@pytest.mark.asyncio
async def test_search_failure_not_cached_as_empty(env):
    _translator(env, ["剑来"])
    _scrapers(env, {}, fail=True)
    res = await env.svc.search("kiem lai")
    assert res.total == 0
    assert ("kiem lai", None) not in env.cache.store


@pytest.mark.asyncio
async def test_search_genuine_empty_is_cached(env):
    _translator(env, ["剑来"])
    _scrapers(env, {})
    await env.svc.search("kiem lai")
    assert env.cache.store[("kiem lai", None)] == []


@pytest.mark.asyncio
async def test_search_local_filter_never_hits_network(env):
    env.mp.setattr(library_index, "_entries", [_entry("n1", "剑来", "Kiếm Lai")])

    async def boom(*a, **k):
        raise AssertionError("không được gọi mạng khi lọc 'local'")
    env.mp.setattr(ss, "query_translator", SimpleNamespace(translate_query=boom))
    env.mp.setattr(env.svc, "_search_scraper_with_timeout", boom)
    res = await env.svc.search("kiem lai", source="local")
    assert [n.id for n in res.results] == ["n1"]


@pytest.mark.asyncio
async def test_search_does_not_mutate_translation_cache(env):
    shared = ["剑来"]

    async def translate_query(q):
        return shared  # trả CHÍNH object — như cache thật
    env.mp.setattr(ss, "query_translator", SimpleNamespace(translate_query=translate_query))
    _scrapers(env, {"剑来": [_novel("w2", "剑来", "Kiếm Lai")]})
    await env.svc.search("kiem lai")
    assert shared == ["剑来"]


# ---------- cache hit + thư viện local ----------

def test_merge_cached_with_local_prepends_and_dedupes(env):
    env.mp.setattr(library_index, "_entries", [_entry("n1", "剑来", "Kiếm Lai")])
    cached = [_novel("w9", "剑来", "Kiếm Lai"), _novel("w8", "遮天", "Già Thiên")]
    merged = env.svc._merge_cached_with_local("kiem lai", cached, None)
    ids = [n.id for n in merged]
    assert ids[0] == "n1" and merged[0].source_id == "local"
    assert "w9" not in ids          # trùng tựa với truyện local
    assert ids[-1] == "w8"
    assert env.svc._merge_cached_with_local("kiem lai", cached, "69shuba") is cached


# ---------- AI tinh chỉnh nền ----------

@pytest.mark.asyncio
async def test_refine_merges_prior_cache_and_overwrites(env):
    env.cache.store[("kiem lai", None)] = [_novel("old", "旧日之书", "Cựu Nhật Chi Thư")]

    async def via_ai(q):
        return ["剑来"]
    env.mp.setattr(ss, "query_translator", SimpleNamespace(translate_query_via_ai=via_ai))
    _scrapers(env, {"剑来": [_novel("new", "剑来", "Kiếm Lai")]})
    await env.svc._refine_search_in_background("kiem lai", "错误词")
    saved = env.cache.store[("kiem lai", None)]
    assert saved[0].id == "new"
    assert {n.id for n in saved} == {"new", "old"}
    assert ("kiem lai", "69shuba") in env.cache.deleted


@pytest.mark.asyncio
async def test_refine_without_new_results_leaves_cache(env):
    async def via_ai(q):
        return ["剑来"]
    env.mp.setattr(ss, "query_translator", SimpleNamespace(translate_query_via_ai=via_ai))
    _scrapers(env, {})
    await env.svc._refine_search_in_background("kiem lai", "错误词")
    assert env.cache.store == {}


def test_schedule_refine_gates():
    svc = ss.SearchService()
    # Không có event loop / từ khóa ngắn / lỗi mạng -> không lên lịch, không nổ
    svc._schedule_ai_refine("kiem", "x")
    svc._schedule_ai_refine("kiem lai truyen", "x", scraper_failed=True)
    assert svc._refine_pending == 0 and svc._refine_recent == {}


@pytest.mark.asyncio
async def test_schedule_refine_cooldown_and_cap(monkeypatch):
    svc = ss.SearchService()
    started = []

    async def fake_refine(q, term):
        started.append(q)
        await asyncio.sleep(0.05)
    monkeypatch.setattr(svc, "_refine_search_in_background", fake_refine)
    for q in ["kiem lai truyen", "kiem lai truyen", "dau pha thuong", "pham nhan tu", "gia thien ky"]:
        svc._schedule_ai_refine(q, "x")
    await asyncio.sleep(0.2)
    assert started.count("kiem lai truyen") == 1      # cooldown
    assert len(started) == ss.REFINE_MAX_PENDING       # trần số tác vụ chờ
    assert svc._refine_pending == 0


# ---------- query_translator: ứng viên chính/phụ, timeout, negative cache ----------

@pytest.fixture
def qt(monkeypatch):
    monkeypatch.setattr(query_translator, "_persist_translation", lambda *a, **k: None)
    keys = []
    yield keys
    for k in keys:
        query_translator._cache.pop(k, None)
        query_translator._neg_cache.pop(k, None)


@pytest.mark.asyncio
async def test_hanviet_guess_is_secondary_and_not_cached(monkeypatch, qt):
    q = "van co than"
    qt.append(q)

    async def fake_do(clean, lower):
        return ["万古第一神"]
    monkeypatch.setattr(query_translator, "_do_translate", fake_do)
    res = await query_translator.translate_query(q)
    assert res[0] == "万古第一神"                 # Google/AI là ứng viên chính
    assert len(res) > 1                           # phiên âm ngược vẫn là ứng viên phụ
    assert q not in query_translator._cache


@pytest.mark.asyncio
async def test_translate_query_timeout_then_background_caches(monkeypatch, qt):
    q = "zzq cham chap xx"
    qt.append(q)
    monkeypatch.setattr(query_translator, "INTERACTIVE_WAIT_SECONDS", 0.05)

    async def slow_do(clean, lower):
        await asyncio.sleep(0.2)
        query_translator._cache[lower] = ["剑来"]
        return ["剑来"]
    monkeypatch.setattr(query_translator, "_do_translate", slow_do)
    assert await query_translator.translate_query(q) == [q]
    await asyncio.sleep(0.3)
    assert await query_translator.translate_query(q) == ["剑来"]


@pytest.mark.asyncio
async def test_negative_cache_ttl(monkeypatch, qt):
    q = "zzq khong dich duoc"
    qt.append(q)
    query_translator._neg_cache[q] = time.monotonic()

    async def must_not_run(clean, lower):
        raise AssertionError("negative cache phải chặn lượt dịch lại")
    monkeypatch.setattr(query_translator, "_do_translate", must_not_run)
    assert await query_translator.translate_query(q) == [q]

    query_translator._neg_cache[q] = time.monotonic() - query_translator.NEGATIVE_CACHE_TTL - 1

    async def ok(clean, lower):
        return ["剑来"]
    monkeypatch.setattr(query_translator, "_do_translate", ok)
    assert await query_translator.translate_query(q) == ["剑来"]


# ---------- xếp hạng & tìm local ----------

def test_rank_results_stable_for_equal_scores():
    a, b, c = _novel("a", "剑来"), _novel("b", "剑来"), _novel("c", "剑来")
    assert [n.id for n in rank_results("kiem lai", [a, b, c])] == ["a", "b", "c"]
    assert [n.id for n in rank_results("kiem lai", [c, b, a])] == ["c", "b", "a"]


def test_local_exact_title_beats_superstrings(env):
    env.mp.setattr(library_index, "_entries", [
        _entry("n1", "凡人修仙传", "Phàm Nhân Tu Tiên"),
        _entry("n2", "仙逆", "Tiên Nghịch"),
        _entry("n4", "仙", "Tiên"),
    ])
    res = env.svc.search_local_library("tien")
    assert res[0].id == "n4"


def test_local_search_ignores_midword_noise(env):
    env.mp.setattr(library_index, "_entries", [_entry("n1", "凡人修仙传", "Phàm Nhân Tu Tiên")])
    assert env.svc.search_local_library("an") == []
    assert env.svc.search_local_library("trong") == []


def test_local_search_truncates_to_max(env):
    env.mp.setattr(library_index, "_entries", [_entry(f"id{i}", f"小说{i}", "Tiểu Thuyết Hay") for i in range(50)])
    assert len(env.svc.search_local_library("tieu thuyet hay")) == ss.LOCAL_MAX_RESULTS
